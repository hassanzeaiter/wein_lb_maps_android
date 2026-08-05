import { Hono } from "hono";

const app = new Hono<{ Bindings: Env }>();

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
  const row = await c.env.DB.prepare(
    "SELECT id, name, category, price, area, landmark_cue, lat, lng, promoted, open_now, rating, reviews_count, phone FROM places WHERE id = ?"
  )
    .bind(c.req.param("id"))
    .first<PlaceRow>();
  if (!row) return c.json({ error: "not_found" }, 404);
  // Detail carries the richer fields the list omits (phone now; hours/photos later).
  return c.json({ ...toPlace(row), phone: row.phone });
});

app.get("/landmarks", async (c) => {
  const { results } = await c.env.DB.prepare(
    "SELECT id, name, kind, lat, lng FROM landmarks ORDER BY name"
  ).all();
  return c.json({ count: results.length, landmarks: results });
});

app.onError((err, c) => {
  console.log(JSON.stringify({ level: "error", msg: "unhandled", error: String(err), path: c.req.path }));
  return c.json({ error: "internal" }, 500);
});

export default app;
