CREATE DATABASE IF NOT EXISTS identity_service;
CREATE DATABASE IF NOT EXISTS friendship_service;
CREATE DATABASE IF NOT EXISTS post_service;
CREATE DATABASE IF NOT EXISTS media_service;
CREATE DATABASE IF NOT EXISTS guess_service;
CREATE DATABASE IF NOT EXISTS notification_service;

GRANT ALL PRIVILEGES ON identity_service.* TO 'mikke'@'%';
GRANT ALL PRIVILEGES ON friendship_service.* TO 'mikke'@'%';
GRANT ALL PRIVILEGES ON post_service.* TO 'mikke'@'%';
GRANT ALL PRIVILEGES ON media_service.* TO 'mikke'@'%';
GRANT ALL PRIVILEGES ON guess_service.* TO 'mikke'@'%';
GRANT ALL PRIVILEGES ON notification_service.* TO 'mikke'@'%';

FLUSH PRIVILEGES;
