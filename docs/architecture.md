# Aiwa architecture

Widget-first Android client. The Activity is a fallback for screens that widgets cannot comfortably host.

`Aiwa widget/app -> ClaudeBridge -> localhost backend -> Claude Code CLI`

The bridge isolates UI from the current Claude Code transport. The APK does not dynamically execute downloaded Kotlin/DEX.
