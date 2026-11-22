# Accepted decisions

- Verify deployment to the Green Monster tablet before product development, following `task/next.md`.
- Use Android's standard shared Recordings folder with the journal layout and exact six-digit filenames in `docs/design.md`.
- An interruption ends saving, with an explicit new arming action required afterward.
- Show an always-live scrolling sound graph while the app is open, with a threshold line and tap-controlled arming; off never saves audio, and armed saving is threshold-gated.
- Retain one file per armed session, omitting quiet periods and joining saved portions during playback. The user selected this after the conflict with `task/vision.md`'s continuous-recording requirement was explained. This supersedes that baseline rule; keep `task/` unchanged.
