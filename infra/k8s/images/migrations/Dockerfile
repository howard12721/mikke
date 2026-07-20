# syntax=docker/dockerfile:1.24
FROM flyway/flyway:12.11.0

COPY base/database/migrations /flyway/migrations
COPY images/migrations/run-migration.sh /flyway/run-migration.sh

RUN chmod +x /flyway/run-migration.sh

ENTRYPOINT ["/flyway/run-migration.sh"]
