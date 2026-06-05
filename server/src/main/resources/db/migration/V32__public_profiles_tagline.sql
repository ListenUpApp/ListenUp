-- V32: add the user-visible tagline to the public_profiles projection (offline profile header).
ALTER TABLE public_profiles ADD COLUMN tagline TEXT;
UPDATE public_profiles SET tagline = (SELECT tagline FROM users WHERE users.id = public_profiles.id);
