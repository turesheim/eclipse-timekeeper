-- Synthetic data for the repository's V1 + V2 schema, not the current JPA schema.
-- Deliberately includes the same task ID in two repositories, Unicode text,
-- multiple activities per task, midnight and ISO-week boundaries, and an empty task.
INSERT INTO PROJECT (NAME, REPOSITORY_URL, EXTERNAL_ID)
VALUES ('Upgrade – Unicode: æøå', 'https://example.invalid/a', 'BASELINE-A'),
       ('Reports', 'https://example.invalid/b', 'BASELINE-B');

INSERT INTO TRACKEDTASK (TASK_ID, REPOSITORY_URL, TASK_URL, TASK_SUMMARY, PROJECT)
VALUES ('1', 'https://example.invalid/a', 'https://example.invalid/a/1', 'Build and dependencies', 'Upgrade – Unicode: æøå'),
       ('1', 'https://example.invalid/b', 'https://example.invalid/b/1', 'Report with Unicode: æøå', 'Reports'),
       ('2', 'https://example.invalid/a', 'https://example.invalid/a/2', 'Task without activities', 'Upgrade – Unicode: æøå');

INSERT INTO ACTIVITY (ID, START_TIME, END_TIME, ADJUSTED, SUMMARY, TASK_ID, REPOSITORY_URL)
VALUES ('00000000-0000-0000-0000-000000000001', '2022-09-19 09:00:00', '2022-09-19 10:30:00', FALSE, 'Investigated the build', '1', 'https://example.invalid/a'),
       ('00000000-0000-0000-0000-000000000002', '2022-09-19 13:00:00', '2022-09-19 14:15:00', TRUE, 'Manually adjusted – Unicode: æøå', '1', 'https://example.invalid/a'),
       ('00000000-0000-0000-0000-000000000003', '2022-09-19 23:30:00', '2022-09-20 00:30:00', FALSE, 'Across midnight', '1', 'https://example.invalid/b'),
       ('00000000-0000-0000-0000-000000000004', '2022-09-18 23:30:00', '2022-09-19 00:30:00', FALSE, 'Across the week boundary', '1', 'https://example.invalid/b'),
       ('00000000-0000-0000-0000-000000000005', '2022-09-20 10:00:00', '2022-09-20 10:45:00', FALSE, 'Exported the report', '1', 'https://example.invalid/b');

INSERT INTO TRACKEDTASK_ACTIVITY (TASK_ID, REPOSITORY_URL, ACTIVITIES_ID)
SELECT TASK_ID, REPOSITORY_URL, ID FROM ACTIVITY;
