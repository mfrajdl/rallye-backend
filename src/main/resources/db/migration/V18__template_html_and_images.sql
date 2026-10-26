-- Restore the explicit HTML column name after V17, without converting content.
ALTER TABLE template_stage RENAME COLUMN instructions TO instructions_html;
ALTER TABLE template_stage ADD CONSTRAINT stage_html_size CHECK (octet_length(instructions_html)<=102400);
ALTER TABLE template_stage ADD CONSTRAINT stage_template_identity UNIQUE(id,race_template_id);
CREATE TABLE template_image (
 id uuid PRIMARY KEY,
 race_template_id uuid NOT NULL REFERENCES race_template(id) ON DELETE CASCADE,
 content_type varchar(10) NOT NULL CHECK(content_type IN ('image/jpeg','image/png')),
 byte_size integer NOT NULL CHECK(byte_size BETWEEN 1 AND 5242880),
 width integer NOT NULL CHECK(width BETWEEN 1 AND 4096),
 height integer NOT NULL CHECK(height BETWEEN 1 AND 4096),
 created_at timestamptz(3) NOT NULL,
 UNIQUE(id,race_template_id)
);
CREATE INDEX template_image_owner ON template_image(race_template_id,created_at,id);
CREATE TABLE template_stage_image (
 stage_id uuid NOT NULL,
 image_id uuid NOT NULL,
 race_template_id uuid NOT NULL,
 PRIMARY KEY(stage_id,image_id),
 FOREIGN KEY(stage_id,race_template_id) REFERENCES template_stage(id,race_template_id) ON DELETE CASCADE,
 FOREIGN KEY(image_id,race_template_id) REFERENCES template_image(id,race_template_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX template_stage_image_lookup ON template_stage_image(image_id);
