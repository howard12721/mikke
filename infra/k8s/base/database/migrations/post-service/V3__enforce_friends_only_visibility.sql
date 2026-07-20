UPDATE posts
SET visibility = 'FRIENDS'
WHERE visibility <> 'FRIENDS';

ALTER TABLE posts
    ADD CONSTRAINT chk_posts_visibility
        CHECK (visibility = 'FRIENDS');
