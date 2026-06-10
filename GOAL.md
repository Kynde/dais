# Do As I Say

A custom linux voice control system.

0) The primary goal is to be able to talk to the claude code (or similar TUI coding agent).
   This requires some support for dictation, e.g. "Please review PR #123 and check out its branch here".
   Or "Select 1" if claude is asking for options or just "Press Enter" if it is asking for a confirmation.
1) With custom I mean that I'm primarily looking for a solution for myself, not necesasrily a generic tool that other can use.
2) It will be run on this laptop, i.e. Fedora linux on this Lenovo X1 Carbon Gen 13 running KDE Plasma on Wayland.
3) We can use whisper-faster to read utterances from the microphone
4) We should silero vad to detect the speech
5) There should be a toggle to enable / disable the voice control system.
6) There should be "recording" indicator, a file existing somewhere would be enough
7) We can later on develop a widget to show the indicator, but that entirely orthogonal
8) Equally I can modify claude statusline or tmux powerline to show the recording indicator

## Unknown

9) For the UX, I must say I have a bluetooth wireless tiny keyboard with 4 buttons, F9..F12 and those can be used freely to improve the experience. I'm not sure yet how, but maybe oen key could be for dictation alone, on and off. Maybe one key could be for "command mode" where the next utterance is expected to be a command, e.g. "Press Enter" or "Select 1". Maybe one key could be for "dictation mode" where the next utterance is expected to be free form text that should be transcribed and sent to the TUI agent. Maybe one key could be for "toggle mode" where the next utterance is expected to be either "on" or "off" to enable or disable the voice control system. I'm not sure, this is something we need to design and possibly experiment with to find out what works best for me.

## Experience

Earlier I did somewhat similar thing already (without the Silero VAD, but with start/stop latch button.
The transcription of that recording was then fed to an LLM model (Opus in Bedrock) to decide on intent
and that generated actions that the system could handle. The idea being that I could just say "Target the claude in tmux under `app`"
and then the next command would be "Review PR #123" and it would know to how to find the correct tmux session and pane and then
execute tmux send keys on that.

That's a solid idea in some sense, and maybe that can be made to work, but I think it's a bit much and doesn't fit the primary use.

The original idea was the LLM could be there used to clean up and fix the talk. And while that's true, my primary use is still to feed that chat to another LLM, thus creating a somewhat unnecesasry layer. I could just instruct the LLM I'm voice controlling with an initial prompt, that it is being voice cotnrolled and that it should expect some of the input to be a bit more noisy and that it should try to fix it up as best as it can. And it can always just stop and ask for clraification if the input is nonsensical. This controlled agent probably has the best context to do the cleaning up and fixing, since it is the one that will be consuming the input and it can ask for clarification if needed.

## New design

Thus, I'm thinking maybe we could split the task somewhat.

Maybe we could use an LLM there internal to locate the correct tmux session and pane to target and that could be behind a different key and as such almost entirely a separate tool even.

Hence here we could focus on just driving the TUI agent with voice and we could assume focus and such having been taken care of and/or tmux target having been provided.

I think one the core design choices next would be to decide whether to target a tmux session or should we go to evdev layer and just follow KDE and tmux focuses, i.e. rely on keyboard output being fed to the right location.

## Implementation

What we did before was:
- json specs for the bus,
- clojure daemon
  . reading ndjson from a unix socket
  . resolving intents with the help of an LLM
  . it had some designated memory slots it could use to store state, e.g. the current tmux target
  . it provided some number of earlier questions/responses as context to the LLM when resolving intent
  . it formed executable actions
  . it had some security layer checking and assessing risk and filtered dangerous stuff, this didn't work very well, it filtered out a lot

I'm thinking we could do json daemon here, too, maybe the json specs again would be a good starting point to pin things down.
Vanilla js is good for tooling. If python is needed, we can do that, too with pixi.

The actual commands can have command line interface, arguments and all, but we need helper tools/ to use and try out easily.



