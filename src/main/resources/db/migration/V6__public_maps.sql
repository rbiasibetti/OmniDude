-- Publicação de mapa para acesso público somente leitura.
-- public_token NULL = não publicado. O token é aleatório (256 bits) e vai na URL; despublicar o apaga, e publicar de novo
-- gera outro, então um link antigo nunca volta a funcionar.
ALTER TABLE maps ADD COLUMN public_token TEXT;
-- O que a visão pública revela além da forma do mapa (a redação é feita no servidor, não na página).
ALTER TABLE maps ADD COLUMN public_show_status INTEGER NOT NULL DEFAULT 1;
ALTER TABLE maps ADD COLUMN public_show_names INTEGER NOT NULL DEFAULT 1;
CREATE UNIQUE INDEX ux_maps_public_token ON maps(public_token) WHERE public_token IS NOT NULL;
