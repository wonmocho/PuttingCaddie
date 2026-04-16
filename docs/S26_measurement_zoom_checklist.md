# S26 Measurement Zoom 1차 체크리스트 (6m/9m)

## 목적
- zoom 이후에도 detector 좌표와 ray/world 좌표 체인이 일치하는지 확인
- transformVersion 변경 시 confirm 샘플 혼입이 없는지 확인
- confirm이 시간 단독이 아니라 version/timestamp/품질 게이트로 동작하는지 확인

## 필수 로그 키
- `zoomRatio`
- `transformVersion`
- `transformTimestampNs`
- `sourceFrameId`
- `detectorFrameTimestampNs`
- `arFrameTimestampNs`
- `cupCenterPreview`
- `cupCenterSensor`
- `projectedCupPx`
- `hitDistanceM`
- `worldSpreadM`
- `centerStdPx`
- `stableFrameCount`
- `confirmAccepted`
- `confirmRejectedReason`

## 판정 기준 (S26 1차)
- `projectedCupPx >= 40` (distance)
- `stableFrameCount >= 8`
- `stabilizing >= 450ms`
- `centerStdPx <= 4`
- `worldSpreadM <= 0.03`
- `abs(detectorTs - arTs) <= 33ms`
- 모든 confirm 샘플이 `current transformVersion`과 동일

## 6m 시나리오 체크
- `transformVersion` 증가 직후 `confirmBuffer` reset 로그 확인
- `confirmRejectedReason`가 단계적으로 줄어드는지 확인
- 최종 confirm 시 `confirmAccepted=true`와 함께 `frame_timestamp_mismatch`가 없어야 함

## 9m 시나리오 체크
- zoom 후 최소 450ms 동안 `MeasureState=STABILIZING` 유지 확인
- `projectedCupPx` 부족 시 `cup_too_small`가 명시되는지 확인
- `world_spread_large`/`center_unstable`가 나와도 reason 기반으로 보류되고, 원인 식별 가능해야 함

## 실측 결과 기록 템플릿
- 거리: `6m` 또는 `9m`
- `measureState` 전이: `...`
- 최종 `transformVersion`: `...`
- 최종 `confirmRejectedReason`: `...`
- 최종 `confirmAccepted`: `true/false`
- 비고: (카메라 흔들림/조도/표면 상태)

