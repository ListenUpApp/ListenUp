-- How each photo is framed (focus and zoom), as ImageFraming JSON bound to the image version it was made for.
-- contributors: beside image_path. users: the avatar's frame, its source of truth. public_profiles: the projection of
-- users.avatar_frame every client syncs. NULL everywhere means "centre crop", which is what every row shows today.
ALTER TABLE contributors ADD COLUMN image_frame TEXT;
ALTER TABLE users ADD COLUMN avatar_frame TEXT;
ALTER TABLE public_profiles ADD COLUMN avatar_frame TEXT;
