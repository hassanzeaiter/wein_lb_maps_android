# Wein backend

The API behind the Wein app: the places directory, community reviews (the hospitality
merge), accounts, business claims, taxi, and the data the admin panel manages.

This folder is the **design + data model** foundation (COD-248/249). The Worker code and
endpoints land in follow-up increments (COD-250/251/252). It lives as a subfolder of the
app repo (monorepo) so the solo workflow stays one repo, one history.

## Stack decision (COD-248)

**Cloudflare Workers + D1 + R2** — recommended, and what the schema targets.

| Concern | Choice | Why |
|---|---|---|
| Compute | **Workers** (TypeScript, [Hono](https://hono.dev) router) | Edge-close to Lebanon, no servers, generous free tier for a demo |
| Database | **D1** (SQLite) | Relational fits the directory/reviews/taxi model; migrations are plain SQL; zero ops |
| Object storage | **R2** | Place & review photos; S3-compatible, no egress fees |
| Auth | **Opaque session tokens** (hashed, in `sessions`) | Simple, revocable first cut; can add Google sign-in via `provider` columns later |

Rationale: this session already has Cloudflare Workers/D1/R2 tooling, the free tiers cover a
pitch/demo, and it keeps the whole thing serverless. *Not locked in* — the schema is plain
SQLite and the API is a normal REST surface, so a swap to Postgres/Node later is mechanical.

> **Not deployed yet.** Provisioning D1/R2 and deploying needs the Cloudflare account
> authorized for this session (the Cloudflare MCP servers require auth first). The schema and
> Worker run **locally** against `wrangler dev` / `--local` D1 without any of that.

## Data model (COD-249)

See [`migrations/0001_init.sql`](migrations/0001_init.sql) — validated against SQLite. Mirrors
the app's `Place`/`PlaceCategory` and adds the product surfaces:

- **Directory** — `categories`, `places` (with denormalised `rating`/`reviews_count`), `landmarks` (nav-cue graph).
- **Accounts** — `users`, `sessions`, `saved_places`.
- **Community reviews (hospitality merge)** — `reviews` (one per user/place, 1–5 + body), `photos` (R2 keys; review or place).
- **Business & moderation** — `business_claims`, `place_edits` (suggested-edit queue).
- **Taxi** — `taxi_rides` (tier, fare, status lifecycle, driver).

Geo note: D1 has no spatial index, so "places near me" is a lat/lng **bounding-box** filter
(indexed) refined by haversine in the Worker — fine at Beirut scale.

## Planned API surface

```
# Directory
GET    /places?q=&category=&near=lat,lng&radius=&sort=promoted   # Explore list (promoted-first)
GET    /places/:id                                                # detail (hours, contact, photos)
GET    /landmarks                                                 # nav-cue graph

# Auth (COD-254)
POST   /auth/signup            POST /auth/login            POST /auth/logout
GET    /me                     GET  /me/saved              POST/DELETE /me/saved/:placeId

# Reviews (COD-257..261)
GET    /places/:id/reviews     POST /places/:id/reviews    PATCH/DELETE /reviews/:id
POST   /uploads/photo          # → R2, returns key

# Business & taxi
POST   /places/:id/claim       POST /places/:id/edits
POST   /taxi/rides             PATCH /taxi/rides/:id        # request → status updates

# Admin (COD-28..32) — role='admin'
GET/PATCH  /admin/reviews      /admin/places      /admin/claims      /admin/taxi
```

## Local dev (once scaffolded)

```bash
cd backend
npm install
npx wrangler d1 migrations apply wein --local   # apply schema to local D1
npx wrangler dev                                 # http://localhost:8787
```

Seed data (`places`, `landmarks`, `categories`) is generated from the app's `PLACES` /
`BEIRUT_LANDMARKS` so the API returns the same directory the demo already shows.

## Next

1. Scaffold the Worker (`wrangler.jsonc`, Hono router, D1/R2 bindings) + seed migration.
2. Implement `GET /places` search and wire the app's Explore/detail to it (COD-262/263).
3. Auth + reviews (the hospitality merge).
