-- Wein backend — initial schema (Cloudflare D1 / SQLite).
-- Mirrors the app's Place/PlaceCategory model and adds the product surfaces:
-- community reviews (the hospitality merge), accounts, business claims, taxi, admin edits.
--
-- Conventions:
--   * ids: TEXT uuid (crypto.randomUUID in the Worker) for user-generated rows;
--     places keep a stable TEXT slug id so the seed + app deep-links are legible.
--   * timestamps: TEXT ISO-8601 UTC (datetime('now')). Booleans: INTEGER 0/1.
--   * D1 has no PostGIS/R*Tree — geo search is a lat/lng bounding-box filter
--     (indexed) refined by haversine in the Worker. See places_lat / places_lng.

PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------------
-- Directory
-- ---------------------------------------------------------------------------

-- Category is the app's PlaceCategory enum key (RESTAURANT, CAFE, …). Kept as a
-- lookup table so the admin panel can manage labels/glyphs without an app release.
CREATE TABLE categories (
    key       TEXT PRIMARY KEY,          -- 'RESTAURANT', 'CAFE', …
    label     TEXT NOT NULL,             -- 'Restaurant', 'Café'
    glyph     TEXT NOT NULL,             -- drawable name, e.g. 'ic_cat_restaurant'
    sort      INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE places (
    id           TEXT PRIMARY KEY,       -- slug, e.g. 'em-sherif'
    name         TEXT NOT NULL,
    category     TEXT NOT NULL REFERENCES categories(key),
    price        INTEGER NOT NULL DEFAULT 0,   -- 0 = n/a, 1..3 = $..$$$
    area         TEXT NOT NULL,                -- neighbourhood
    landmark_cue TEXT NOT NULL,                -- Lebanese-style location cue
    lat          REAL NOT NULL,
    lng          REAL NOT NULL,
    phone        TEXT,
    hours        TEXT,                         -- JSON opening hours (nullable for now)
    promoted     INTEGER NOT NULL DEFAULT 0,
    open_now     INTEGER NOT NULL DEFAULT 1,   -- until real hours logic lands
    -- Denormalised review aggregates, recomputed on review write (COD-261).
    rating       REAL NOT NULL DEFAULT 0,
    reviews_count INTEGER NOT NULL DEFAULT 0,
    claimed_by   TEXT REFERENCES users(id),    -- business claim (COD-264/31)
    created_at   TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX places_category  ON places(category);
CREATE INDEX places_promoted  ON places(promoted, rating DESC);
CREATE INDEX places_lat       ON places(lat);
CREATE INDEX places_lng       ON places(lng);

-- The landmark nav-cue graph (BEIRUT_LANDMARKS) — used to phrase turn directions.
CREATE TABLE landmarks (
    id   TEXT PRIMARY KEY,               -- slug
    name TEXT NOT NULL,
    kind TEXT NOT NULL,                  -- mall, square, mosque, seaside, junction…
    lat  REAL NOT NULL,
    lng  REAL NOT NULL
);

-- ---------------------------------------------------------------------------
-- Accounts (COD-254/255)
-- ---------------------------------------------------------------------------

CREATE TABLE users (
    id            TEXT PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    name          TEXT NOT NULL,
    avatar_url    TEXT,
    -- Auth: email+password (argon2/bcrypt hash) or a federated provider.
    password_hash TEXT,
    provider      TEXT,                  -- null = password, else 'google' etc.
    provider_id   TEXT,
    role          TEXT NOT NULL DEFAULT 'user',  -- 'user' | 'admin'
    created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Opaque session tokens (hashed). Simpler than JWT for a first cut; revocable.
CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    expires_at TEXT NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);

CREATE TABLE saved_places (              -- bookmarks (Profile → Saved places)
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    place_id   TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, place_id)
);

-- ---------------------------------------------------------------------------
-- Community reviews — the hospitality-product merge (COD-257..261)
-- ---------------------------------------------------------------------------

CREATE TABLE reviews (
    id         TEXT PRIMARY KEY,
    place_id   TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rating     INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body       TEXT NOT NULL DEFAULT '',
    status     TEXT NOT NULL DEFAULT 'published',  -- 'published' | 'hidden' | 'flagged'
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (place_id, user_id)           -- one review per user per place
);
CREATE INDEX reviews_place ON reviews(place_id, created_at DESC);
CREATE INDEX reviews_user  ON reviews(user_id);

-- Photos live in R2; we store the object key + who uploaded it. Used for both
-- review photos (review_id set) and place photos (review_id null).
CREATE TABLE photos (
    id         TEXT PRIMARY KEY,
    place_id   TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    review_id  TEXT REFERENCES reviews(id) ON DELETE CASCADE,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    r2_key     TEXT NOT NULL,
    status     TEXT NOT NULL DEFAULT 'published',
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX photos_place  ON photos(place_id);
CREATE INDEX photos_review ON photos(review_id);

-- ---------------------------------------------------------------------------
-- Business tools & moderation (COD-264 / 29 / 30 / 31)
-- ---------------------------------------------------------------------------

CREATE TABLE business_claims (
    id         TEXT PRIMARY KEY,
    place_id   TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status     TEXT NOT NULL DEFAULT 'pending',  -- 'pending' | 'approved' | 'rejected'
    note       TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX business_claims_place ON business_claims(place_id);

-- User-suggested edits to a place, queued for admin approval (COD-29).
CREATE TABLE place_edits (
    id         TEXT PRIMARY KEY,
    place_id   TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    user_id    TEXT REFERENCES users(id) ON DELETE SET NULL,
    payload    TEXT NOT NULL,           -- JSON of proposed field changes
    status     TEXT NOT NULL DEFAULT 'pending',
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ---------------------------------------------------------------------------
-- Taxi (COD-270..272) — simulation now, real request handling next
-- ---------------------------------------------------------------------------

CREATE TABLE taxi_rides (
    id            TEXT PRIMARY KEY,
    user_id       TEXT REFERENCES users(id) ON DELETE SET NULL,
    dest_place_id TEXT REFERENCES places(id) ON DELETE SET NULL,
    dest_name     TEXT NOT NULL,
    dest_lat      REAL NOT NULL,
    dest_lng      REAL NOT NULL,
    origin_lat    REAL NOT NULL,
    origin_lng    REAL NOT NULL,
    tier          TEXT NOT NULL,         -- 'Economy' | 'Comfort' | 'Van XL'
    fare_usd      REAL NOT NULL,
    distance_m    REAL NOT NULL,
    duration_s    REAL NOT NULL,
    status        TEXT NOT NULL DEFAULT 'requested',
                  -- 'requested' | 'assigned' | 'arriving' | 'completed' | 'cancelled'
    driver_name   TEXT,
    driver_plate  TEXT,
    created_at    TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at    TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX taxi_rides_user   ON taxi_rides(user_id, created_at DESC);
CREATE INDEX taxi_rides_status ON taxi_rides(status);
