# Discord test fixtures

Made with ffmpeg's libopus encoder, not with our own Ogg code, so the decoder test can't agree with itself:

```
ffmpeg -f lavfi -i "sine=frequency=440:duration=3" -ac 1 -ar 48000 -c:a libopus -b:a 128k -vbr off \
  -frame_duration 20 -page_duration 3000000 voice-3s.ogg
head -c 1500 voice-3s.ogg > voice-truncated.ogg
```

48 kHz mono Ogg Opus, like a Discord voice message. 128 kbit/s packets are over 255 bytes (lacing values of 255), and 3-second pages fill up, so one packet continues onto the next page.
