CREATE USER demo_app PASSWORD '{{ DEMO_APP_PG_PASSWORD }}';
GRANT ALL ON SCHEMA public to demo_app;