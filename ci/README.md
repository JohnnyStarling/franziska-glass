# CI template

`continuity.github-actions.yml` runs the same Python/Node checks documented for the source release on an Ubuntu runner with Python 3.11 and Node 22. It needs only `contents: read` and no credentials, devices or model service.

It is supplied as a template, not an active green build badge. To enable it in a repository you administer, copy it to `.github/workflows/continuity.yml` with an account authorized to manage workflows.

Local commands:

```sh
python3 -m unittest discover -s tests -p 'test_continuity.py' -v
node --test tests/continuity-plugin.test.mjs
```
