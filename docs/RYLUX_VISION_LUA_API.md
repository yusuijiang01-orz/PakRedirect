# RYLUX Vision Lua API (2.6.1)

This API is installed by LegacyLuaRuntime as the global table ryluxVision. It is additive: legacy findPic/findPicAdaptive/findColor/tomocr* calls remain available while scripts migrate one recognition path at a time.

## Runtime and calibration

- ryluxVision.status() -> opencv, opencvStatus, opencvVersion, opencvMatches, legacyFallbacks
- ryluxVision.viewport() -> left/top/right/bottom, width/height, calibrated, rotation, screenWidth/screenHeight
- ryluxVision.mapPoint(x,y) -> screenX, screenY
- ryluxVision.mapRect(left,top,right,bottom) -> mapped bounds
- ryluxVision.fitViewport(anchors, referenceWidth, referenceHeight, maxResidualPixels) -> calibrated/status/bounds. Each anchor has refX/refY/screenX/screenY.

## Recognition

- ryluxVision.findPic(left,top,right,bottom,templatePath,maskColor,method,threshold) -> legacy-compatible boolean and coordinates. OpenCV proposes candidates; candidates pass the legacy strict pixel verifier; misses fall back to the legacy matcher.
- ryluxVision.readDigits(left,top,right,bottom,min,max) -> status/value/confidence/raw/confirmed. It captures two independent frames; disagreement or uncertain OCR returns UNKNOWN.
- ryluxVision.scanInventory35(left,top,right,bottom,emptyTemplate,redPotionTemplate,bluePotionTemplate,otherTemplate,threshold,margin,insetX,insetY) -> 35 independent cells plus unknown/complete/redPotion/bluePotion/empty.
- ryluxVision.scanPotionStacks35(same arguments) -> inventory result with potionStacks; each potion count is independently read and double-frame checked.

## State machine

- ryluxVision.state.current() -> current state name.
- ryluxVision.state.observe(state, confidence, independentFrame) -> true only when accepted.
- ryluxVision.state.beginRecovery() -> bounded recovery permission.
- ryluxVision.state.recoverySucceeded(state) / recoveryFailed().
- ryluxVision.state.canMutateInventory() and canConfirmSale() expose transaction guards.

## Mandatory safety rules

1. UNKNOWN is not EMPTY, not zero, and not permission to click, buy, sell, or discard.
2. Require inventory scan complete=true before using aggregate empty/red/blue counts for a trade decision.
3. Require each potion stack confirmed=true and count > 0 before arithmetic or purchase/sale.
4. Require two independent observations for sale/confirmation states; canConfirmSale() must be true immediately before the confirm tap.
5. A failed/ambiguous recovery ends the transaction and preserves the failure evidence; do not add unlimited retries or lower thresholds to hide failures.
6. Legacy APIs remain fallback-compatible. A script should migrate one high-risk recognition path at a time and retain a tested rollback route.

## Current migration state

- Legacy findPic already routes through OpenCV multi-scale candidates with strict legacy verification and a legacy fallback.
- Numeric reads and 35-cell inventory/potion APIs are available through ryluxVision.
- Existing Lua scripts have not all been migrated to call the new table directly. Do not claim every historical OCR node has been replaced until the relevant script source is migrated and replayed against captured emulator frames.
