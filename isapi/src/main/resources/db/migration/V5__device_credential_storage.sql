-- Ciphertext includes a version, random IV and authentication tag.
-- Existing credentials are converted transactionally by DeviceCredentialUpgrade.
ALTER TABLE devices ALTER COLUMN password TYPE TEXT;
