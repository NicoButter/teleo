# Jetpack Compose reference patterns

These are illustrative fragments. Confirm imports and API support for the actual Android/Compose versions in the buyer's project before applying them.

## Icon-only control
```kotlin
IconButton(onClick = onStop) {
    Icon(
        imageVector = Icons.Default.Stop,
        contentDescription = "Stop speech recognition"
    )
}
```
Ensure the accessible name matches the action as understood by the target user. A null description on an icon-only actionable button can leave the action unclear unless a parent supplies a suitable semantic name.

## Status and partial transcription
```kotlin
Column {
    Text(text = if (isListening) "Microphone listening" else "Microphone off")
    Text(text = finalTranscript)
    Text(text = partialTranscript)
}
```
Do **not** indiscriminately add `liveRegion` to every partial-result update: high-frequency recognition hypotheses could flood TalkBack. Consider an explicit final-result announcement, throttled status changes and manual review.

## Gesture-only interaction
When a full-screen press-and-hold starts recording, provide a visibly discoverable `Button` or other accessible control for start/stop. Test switch/keyboard/TalkBack access; raw `pointerInput` alone isn't equivalent to an accessible button.

## Large text
Prefer responsive constraints and line wrapping to single-line ellipsis for essential caption content. Test actual rendered UI under extreme font scales; a fixed `sp` size is not by itself proof of readability.

## Permissions
Provide a clear explanation before `RECORD_AUDIO`, an alternative path when denied, and an explicit visible stopped/listening state. Stop recognition and release resources at appropriate lifecycle boundaries.
