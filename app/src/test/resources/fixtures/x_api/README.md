# X API Test Fixtures

- `initial_fetch_99.json`
  - Initial sync sample. Contains 99 posts to simulate first launch (`since_id = null`, `max_results = 99`).
- `no_new_posts.json`
  - Delta sync sample with no new data (`result_count = 0`).
- `mixed_media_delta.json`
  - Delta sync sample containing `photo`, `video`, and `animated_gif` media types.

These fixtures are intended for unit tests and should not hit real X API endpoints.
