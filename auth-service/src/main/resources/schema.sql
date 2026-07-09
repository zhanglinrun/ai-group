CREATE DATABASE IF NOT EXISTS `auth_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `auth_db`;

CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(64) NOT NULL,
    `password` VARCHAR(128) NOT NULL,
    `email` VARCHAR(128) DEFAULT NULL,
    `phone` VARCHAR(32) DEFAULT NULL,
    `role` VARCHAR(32) NOT NULL DEFAULT 'USER',
    `status` TINYINT NOT NULL DEFAULT 1,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 运营端管理员种子账号：admin / Admin@123456（BCrypt），role=ADMIN。
-- 经普通登录接口签发的 JWT 携带 ADMIN 角色，网关注入 X-Role 后各服务据此放行管理接口。
INSERT INTO `user` (`username`, `password`, `email`, `role`, `status`) VALUES
('admin', '$2b$10$7nO4GggILWjB1a1il5dEUuq63JC2qqWTZJjRyshHc53S1nJfyv0uq', 'admin@ai-group.local', 'ADMIN', 1)
ON DUPLICATE KEY UPDATE `role` = 'ADMIN', `status` = 1;
