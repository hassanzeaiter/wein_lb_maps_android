-- Demo users + community reviews so place detail shows real reviews from the backend
-- (the app's bundled reviewPool is only a fallback). One review per user per place.

INSERT INTO users (id, email, name, role) VALUES
  ('u_rami',  'rami@example.com',  'Rami K.',  'user'),
  ('u_nour',  'nour@example.com',  'Nour A.',  'user'),
  ('u_jad',   'jad@example.com',   'Jad H.',   'user'),
  ('u_maya',  'maya@example.com',  'Maya S.',  'user'),
  ('u_karim', 'karim@example.com', 'Karim T.', 'user'),
  ('u_lynn',  'lynn@example.com',  'Lynn M.',  'user');

INSERT INTO reviews (id, place_id, user_id, rating, body, created_at) VALUES
  ('rv_es1', 'em-sherif',     'u_rami',  5, 'Exceptional Lebanese mezze. Easy to find with the landmark directions — right off Damascus Rd.', '2026-07-28 20:15:00'),
  ('rv_es2', 'em-sherif',     'u_nour',  4, 'Beautiful setting and great service. Busy on weekends, book ahead.', '2026-07-22 21:00:00'),
  ('rv_es3', 'em-sherif',     'u_jad',   5, 'One of the best in Achrafieh. The tabbouleh alone is worth it.', '2026-07-15 19:40:00'),
  ('rv_cy1', 'caf-younes',    'u_maya',  5, 'Best coffee in Hamra, hands down. Cozy spot off the main street.', '2026-07-26 10:05:00'),
  ('rv_cy2', 'caf-younes',    'u_karim', 4, 'Great beans and atmosphere. Can get crowded mid-morning.', '2026-07-19 09:30:00'),
  ('rv_abc1','abc-achrafieh', 'u_lynn',  4, 'The big mall at Sassine — has everything. Parking fills up on weekends.', '2026-07-24 17:20:00'),
  ('rv_abc2','abc-achrafieh', 'u_rami',  5, 'Go-to for shopping and cinema. Easy landmark, everyone knows it.', '2026-07-10 15:00:00');
