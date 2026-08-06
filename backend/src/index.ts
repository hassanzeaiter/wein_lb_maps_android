import { Hono } from "hono";
import {
  type AuthEnv,
  bearer,
  createSession,
  hashPassword,
  optionalUser,
  requireAuth,
  sha256Hex,
  verifyPassword,
} from "./auth";

const app = new Hono<AuthEnv>();

// ---- helpers ---------------------------------------------------------------

interface PlaceRow {
  id: string;
  name: string;
  category: string;
  price: number;
  area: string;
  landmark_cue: string;
  lat: number;
  lng: number;
  promoted: number;
  open_now: number;
  rating: number;
  reviews_count: number;
  phone: string | null;
}

/** Shape the app consumes (mirrors the Kotlin `Place`). */
function toPlace(r: PlaceRow, distanceM?: number) {
  return {
    id: r.id,
    name: r.name,
    category: r.category,
    price: r.price,
    area: r.area,
    landmark: r.landmark_cue,
    lat: r.lat,
    lng: r.lng,
    promoted: r.promoted === 1,
    openNow: r.open_now === 1,
    rating: r.rating,
    reviews: r.reviews_count,
    ...(distanceM !== undefined ? { distanceM: Math.round(distanceM) } : {}),
  };
}

function haversine(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// ---- routes ----------------------------------------------------------------

app.get("/", (c) => c.json({ service: "wein-backend", ok: true }));

/**
 * Explore directory search.
 *   ?q=          text over name / area / landmark cue
 *   ?category=   PlaceCategory key (RESTAURANT, CAFE, …)
 *   ?near=lat,lng&radius=meters   geo filter (bounding box + haversine), sorted by distance
 *   ?limit=      default 50, max 200
 * Without ?near, results are promoted-first then by rating (matches the app's Explore sort).
 */
app.get("/places", async (c) => {
  const q = c.req.query("q")?.trim();
  const category = c.req.query("category")?.trim();
  const near = c.req.query("near")?.trim();
  const radius = Math.min(Number(c.req.query("radius")) || 3000, 50000);
  const limit = Math.min(Number(c.req.query("limit")) || 50, 200);

  const where: string[] = [];
  const params: unknown[] = [];

  if (category) {
    where.push("category = ?");
    params.push(category);
  }
  if (q) {
    where.push("(name LIKE ?1 OR area LIKE ?1 OR landmark_cue LIKE ?1)".replace(/\?1/g, "?"));
    const like = `%${q}%`;
    params.push(like, like, like);
  }

  let lat = 0;
  let lng = 0;
  const geo = !!near;
  if (geo) {
    const [la, ln] = near!.split(",").map(Number);
    if (Number.isFinite(la) && Number.isFinite(ln)) {
      lat = la;
      lng = ln;
      // Bounding box (indexed) — refined by haversine below.
      const dLat = radius / 111320;
      const dLng = radius / (111320 * Math.cos((lat * Math.PI) / 180) || 1);
      where.push("lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?");
      params.push(lat - dLat, lat + dLat, lng - dLng, lng + dLng);
    }
  }

  const sql =
    "SELECT id, name, category, price, area, landmark_cue, lat, lng, promoted, open_now, rating, reviews_count, phone FROM places" +
    (where.length ? " WHERE " + where.join(" AND ") : "") +
    (geo ? "" : " ORDER BY promoted DESC, rating DESC") +
    " LIMIT ?";
  params.push(geo ? 500 : limit); // geo: over-fetch the box, then trim after distance sort

  const { results } = await c.env.DB.prepare(sql).bind(...params).all<PlaceRow>();

  let places;
  if (geo) {
    places = results
      .map((r) => ({ r, d: haversine(lat, lng, r.lat, r.lng) }))
      .filter((x) => x.d <= radius)
      .sort((a, b) => a.d - b.d)
      .slice(0, limit)
      .map((x) => toPlace(x.r, x.d));
  } else {
    places = results.map((r) => toPlace(r));
  }

  return c.json({ count: places.length, places });
});

app.get("/places/:id", async (c) => {
  const id = c.req.param("id");
  const row = await c.env.DB.prepare(
    "SELECT id, name, category, price, area, landmark_cue, lat, lng, promoted, open_now, rating, reviews_count, phone FROM places WHERE id = ?"
  )
    .bind(id)
    .first<PlaceRow>();
  if (!row) return c.json({ error: "not_found" }, 404);
  // If the request is authenticated, report whether this user has bookmarked the place
  // so the app can render the Save button in its correct on/off state.
  const user = await optionalUser(c);
  const saved = user
    ? !!(await c.env.DB.prepare("SELECT 1 FROM saved_places WHERE user_id = ? AND place_id = ?")
        .bind(user.id, id)
        .first())
    : false;
  // Detail carries the richer fields the list omits (phone now; hours/photos later).
  return c.json({ ...toPlace(row), phone: row.phone, saved });
});

/** Published community reviews for a place, newest first (COD-260). */
app.get("/places/:id/reviews", async (c) => {
  const { results } = await c.env.DB.prepare(
    `SELECT r.id, r.rating, r.body, r.created_at AS createdAt, u.name AS author, u.avatar_url AS avatarUrl
     FROM reviews r JOIN users u ON u.id = r.user_id
     WHERE r.place_id = ? AND r.status = 'published'
     ORDER BY r.created_at DESC`
  )
    .bind(c.req.param("id"))
    .all();
  return c.json({ count: results.length, reviews: results });
});

/** Post (or update) the signed-in user's review for a place (COD-259). */
app.post("/places/:id/reviews", requireAuth, async (c) => {
  const placeId = c.req.param("id");
  const { rating, body } = await c.req.json<Record<string, unknown>>().catch((): Record<string, unknown> => ({}));
  const r = Number(rating);
  if (!Number.isInteger(r) || r < 1 || r > 5 || typeof body !== "string") {
    return c.json({ error: "invalid_input" }, 400);
  }
  const place = await c.env.DB.prepare("SELECT id FROM places WHERE id = ?").bind(placeId).first();
  if (!place) return c.json({ error: "not_found" }, 404);

  const user = c.get("user");
  // One review per user per place — insert, or update the existing one.
  await c.env.DB.prepare(
    `INSERT INTO reviews (id, place_id, user_id, rating, body) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(place_id, user_id) DO UPDATE SET rating = excluded.rating, body = excluded.body, updated_at = datetime('now')`
  )
    .bind(crypto.randomUUID(), placeId, user.id, r, body.trim())
    .run();

  // Recompute the denormalised place aggregate (COD-261).
  await c.env.DB.prepare(
    `UPDATE places SET
       rating = COALESCE((SELECT ROUND(AVG(rating), 1) FROM reviews WHERE place_id = ?1 AND status = 'published'), 0),
       reviews_count = (SELECT COUNT(*) FROM reviews WHERE place_id = ?1 AND status = 'published')
     WHERE id = ?1`
  )
    .bind(placeId)
    .run();

  return c.json({ ok: true, review: { author: user.name, rating: r, body: body.trim() } }, 201);
});

/** Bookmark a place for the signed-in user (Profile → Saved places). Idempotent. */
app.post("/places/:id/save", requireAuth, async (c) => {
  const placeId = c.req.param("id");
  const place = await c.env.DB.prepare("SELECT id FROM places WHERE id = ?").bind(placeId).first();
  if (!place) return c.json({ error: "not_found" }, 404);
  await c.env.DB.prepare(
    "INSERT INTO saved_places (user_id, place_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
  )
    .bind(c.get("user").id, placeId)
    .run();
  return c.json({ ok: true, saved: true });
});

/** Remove a bookmark. Idempotent. */
app.delete("/places/:id/save", requireAuth, async (c) => {
  await c.env.DB.prepare("DELETE FROM saved_places WHERE user_id = ? AND place_id = ?")
    .bind(c.get("user").id, c.req.param("id"))
    .run();
  return c.json({ ok: true, saved: false });
});

app.get("/landmarks", async (c) => {
  const { results } = await c.env.DB.prepare(
    "SELECT id, name, kind, lat, lng FROM landmarks ORDER BY name"
  ).all();
  return c.json({ count: results.length, landmarks: results });
});

// ---- auth (COD-254) --------------------------------------------------------

app.post("/auth/signup", async (c) => {
  const { email, name, password } = await c.req.json<Record<string, unknown>>().catch((): Record<string, unknown> => ({}));
  if (typeof email !== "string" || typeof name !== "string" || typeof password !== "string" ||
      !email.includes("@") || name.trim().length < 1 || password.length < 6) {
    return c.json({ error: "invalid_input" }, 400);
  }
  const existing = await c.env.DB.prepare("SELECT id FROM users WHERE email = ?").bind(email).first();
  if (existing) return c.json({ error: "email_taken" }, 409);
  const id = crypto.randomUUID();
  await c.env.DB.prepare("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, ?, ?)")
    .bind(id, email, name.trim(), await hashPassword(password))
    .run();
  const token = await createSession(c.env.DB, id);
  return c.json({ token, user: { id, email, name: name.trim(), role: "user" } }, 201);
});

app.post("/auth/login", async (c) => {
  const { email, password } = await c.req.json<Record<string, unknown>>().catch((): Record<string, unknown> => ({}));
  if (typeof email !== "string" || typeof password !== "string") {
    return c.json({ error: "invalid_input" }, 400);
  }
  const u = await c.env.DB.prepare(
    "SELECT id, email, name, role, password_hash FROM users WHERE email = ?"
  )
    .bind(email)
    .first<{ id: string; email: string; name: string; role: string; password_hash: string | null }>();
  if (!u || !u.password_hash || !(await verifyPassword(password, u.password_hash))) {
    return c.json({ error: "invalid_credentials" }, 401);
  }
  const token = await createSession(c.env.DB, u.id);
  return c.json({ token, user: { id: u.id, email: u.email, name: u.name, role: u.role } });
});

app.post("/auth/logout", requireAuth, async (c) => {
  const token = bearer(c);
  if (token) await c.env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(await sha256Hex(token)).run();
  return c.json({ ok: true });
});

app.get("/me", requireAuth, (c) => c.json({ user: c.get("user") }));

// ---- Profile surfaces (COD-255) --------------------------------------------

/** The user's bookmarked places, newest-saved first — same shape as GET /places. */
app.get("/me/saved", requireAuth, async (c) => {
  const { results } = await c.env.DB.prepare(
    `SELECT p.id, p.name, p.category, p.price, p.area, p.landmark_cue, p.lat, p.lng,
            p.promoted, p.open_now, p.rating, p.reviews_count, p.phone
     FROM saved_places s JOIN places p ON p.id = s.place_id
     WHERE s.user_id = ?
     ORDER BY s.created_at DESC`
  )
    .bind(c.get("user").id)
    .all<PlaceRow>();
  const places = results.map((r) => ({ ...toPlace(r), saved: true }));
  return c.json({ count: places.length, places });
});

/** The user's own reviews, newest first, each carrying its place for display + deep-link. */
app.get("/me/reviews", requireAuth, async (c) => {
  const { results } = await c.env.DB.prepare(
    `SELECT r.id, r.rating, r.body, r.created_at AS createdAt,
            p.id AS placeId, p.name AS placeName, p.category AS placeCategory, p.area AS placeArea
     FROM reviews r JOIN places p ON p.id = r.place_id
     WHERE r.user_id = ?
     ORDER BY r.created_at DESC`
  )
    .bind(c.get("user").id)
    .all();
  return c.json({ count: results.length, reviews: results });
});

/** The user's uploaded photos, newest first (R2 uploads land in COD-253; empty until then). */
app.get("/me/photos", requireAuth, async (c) => {
  const { results } = await c.env.DB.prepare(
    `SELECT ph.id, ph.r2_key AS r2Key, ph.created_at AS createdAt,
            p.id AS placeId, p.name AS placeName
     FROM photos ph JOIN places p ON p.id = ph.place_id
     WHERE ph.user_id = ? AND ph.status = 'published'
     ORDER BY ph.created_at DESC`
  )
    .bind(c.get("user").id)
    .all();
  return c.json({ count: results.length, photos: results });
});

app.onError((err, c) => {
  console.log(JSON.stringify({ level: "error", msg: "unhandled", error: String(err), path: c.req.path }));
  return c.json({ error: "internal" }, 500);
});

export default app;
