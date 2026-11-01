# Camera gestures

The tablet's front camera can run keys for you: hold a hand pose still for half a second, or just
walk away from the desk. Turn it on in ⚙ → **Camera gestures** (off by default; Android asks for
the camera the first time).

Recognition runs on the tablet ([MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/vision/gesture_recognizer)):
frames are only kept in memory, one at a time, and nothing is stored or sent anywhere. The camera
is used only while the macropad is on screen (its dimmed clock included).

## Gestures

**Hand poses** — hold still for half a second:

| Pose | Default action |
|---|---|
| Open palm ✋ | Play / Pause |
| Thumb up 👍 | Volume + |
| Thumb down 👎 | Volume − |
| Fist ✊, one ☝ / two ✌ / three / four fingers, OK 👌, Rock 🤘, Call me 🤙, I love you 🤟, Point left 👈 / right 👉 | nothing (assign what you like) |

Every pose can do something else with the **left hand** (⚙ → Camera gestures → Left hand…); an
empty left-hand pose does what the right hand's does.

**Movement:**

| Gesture | Default action |
|---|---|
| Swipe left / right with an open hand | Next / previous page |
| Swipe up / down with an open hand | nothing |
| Pinch 🤏 (thumb on index) and move up or down | Volume slider: one step per ~5 % of the view's height |

**You:**

| Gesture | Default action |
|---|---|
| **You leave** (no face or hand in view for 3 min) | **Away**: locks the PC and dims the tablet |
| **You come back** | nothing; the dimmed clock wakes up by itself |

Each one takes any action from the [macro language](macro-language.md), including network steps
(e.g. `ha:script.turn_on script.desktop_jf_wake` when you come back). Pinch and drag takes a
slider action (`slide:volume`, `slide:scroll`, `slide:zoom`, …). Tap a gesture in
⚙ → Camera gestures to change it; the time before "You leave" is set there too. Gesture actions
are part of the layout, so they are in backups and exports.

A pose fires once; to repeat it, drop the hand (or change pose) and show it again. A moving hand
never counts as a pose, so swiping with an open palm doesn't also play / pause. Like a touch, a
gesture on the dimmed clock only wakes it.

## Tips

- Face the tablet from about arm's length; the face check works up to ~2 m.
- The dialog shows what the camera is doing, e.g.
  `watching · 2.6 frames/s · … · face in 4 of 5 checks`. "face in 0 of 5" while you sit in front
  of it means the camera can't see you (covered, too dark, or pointing elsewhere).
- Speed depends on the tablet: about 2–3 frames a second on a Fire HD 8 (enough for held poses
  and slow pinch and drag, too few for swipes), up to 15 on faster tablets such as a Galaxy Tab S5e. While nobody is in view it only looks for a face once a second.
