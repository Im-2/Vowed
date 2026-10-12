# Music credits: demo video

The background music in `demo-video/vowed-demo-3min.mp4` is **original**, generated in code by `demo-video/build/audio.py` (function `pad_music`)
each time the video is built. It uses no samples, no recordings and no third-party track, so there is no licence to clear.

How it is made:
- four slow chords in D major (D, Bm, G, A voicings), 8 seconds each, looped for the length of the video
- every note is a sine wave plus a soft triangle wave, slightly detuned between the left and right channels, with a sub note an octave below the root
- slow attack and release on each chord, a gentle 23-second swell in level
- 900 Hz low-pass filter (2nd-order Butterworth) and a light reverb (convolution with a 2.4-second decaying noise impulse)
- in the mix: 2 s fade in, 3 s fade out, ducked to about 22 dB under the narration while it speaks and about 15 dB under the voice level in pauses

Licence: the music was written for this project and may be used with it without restriction.
