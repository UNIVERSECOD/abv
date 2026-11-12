-- A full IPv6 literal with brackets and a five-digit port can exceed 45 characters.
ALTER TABLE devices ALTER COLUMN ip TYPE VARCHAR(64);
