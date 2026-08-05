-- Demo contact numbers so the place-detail "Call" action has real data to dial.
-- (The app's bundled seed has no phones; these come only from the backend.)
UPDATE places SET phone = '+961 1 200 400' WHERE id = 'em-sherif';
UPDATE places SET phone = '+961 1 750 175' WHERE id = 'caf-younes';
UPDATE places SET phone = '+961 1 209 109' WHERE id = 'abc-achrafieh';
UPDATE places SET phone = '+961 1 369 000' WHERE id = 'phoenicia-hotel';
