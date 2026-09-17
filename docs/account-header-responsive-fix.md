# Account header responsive fix

This patch removes the rounded-card shell around the logged-in account header and prevents the adaptive runtime layer from reparenting the avatar/text/button hierarchy after the home screen has already been built.

## Behavior

- Account area uses a transparent background.
- Narrow screens: avatar + account text stay on the first row; a full-width `查看账号` button is placed below.
- Wider screens: avatar + text + `查看` remain on one row.
- Avatar size and typography scale with the actual viewport width.
- Game hero sizing remains responsive.
- Landscape/short-height game detail scrolling remains enabled.
