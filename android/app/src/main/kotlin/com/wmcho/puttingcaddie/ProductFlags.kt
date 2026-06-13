package com.wmcho.puttingcaddie



/**

 * PuttingCaddy (제품) vs PuttingCaddy+ (연구) 분기.

 * Launch Patch 1.1.6: 스토어 1.1.5 베이스 + warmup/outdoor만 추가.

 */

object ProductFlags {

    /** true: 줌 UI·상태·렌더 줌·ray inverse 줌 분기 전부 1.0 / 풀프레임 기준으로 고정 */

    const val ZOOM_DISABLED: Boolean = true



    /**

     * 스토어 1.1.5와 동일: 거리 표시·확정 경로 (하드 가드 UI 재측정 없음).

     */

    const val DISTANCE_ONLY_PRODUCT: Boolean = true



    /** IDLE warmup threshold outdoor 기준(18/24). 거리 파이프라인 무관. */

    const val OUTDOOR_WARMUP_RELAXED: Boolean = true



    /** 프리뷰 contrast/gamma/saturation + UI 고대비. 거리 로직 무관. */

    const val OUTDOOR_HIGH_VISIBILITY: Boolean = true

}

