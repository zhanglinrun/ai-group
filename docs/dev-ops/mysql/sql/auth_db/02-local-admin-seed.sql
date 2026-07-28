-- Local demo only. Existing users are never promoted, re-enabled or overwritten.
USE `auth_db`;

INSERT INTO `user` (`username`, `password`, `email`, `role`, `status`)
VALUES (
  'admin',
  '$2b$10$7nO4GggILWjB1a1il5dEUuq63JC2qqWTZJjRyshHc53S1nJfyv0uq',
  'admin@ai-group.local',
  'ADMIN',
  1
)
ON DUPLICATE KEY UPDATE `id` = `id`;
