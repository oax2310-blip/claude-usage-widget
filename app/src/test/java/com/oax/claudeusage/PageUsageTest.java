package com.oax.claudeusage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.time.ZoneId;

import org.junit.Test;

public class PageUsageTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long HOUR = UsageCalc.HOUR;
    private static final long MINUTE = UsageCalc.MINUTE;
    /** 읽은 시각: 2026-10-03(토) 오전 8시 */
    private static final long NOW = UsageCalcTest.at(10, 3, 8, 0);

    private static PageUsage.SessionRead session(String page) {
        return PageUsage.session(page, NOW, SEOUL);
    }
    /** 실제 폰 화면(Pro, 2026-10) 그대로 */
    @Test
    public void realKoreanProPage() {
        String page = "설정\n일반\n계정\n개인정보보호\n결제\n사용량\n사용량\nPro\n"
                + "주의하세요. 이 속도라면 토요일 재설정 전에 사용량이 소진됩니다. 소진 예상 시점: 내일 저녁\n"
                + "플랜 업그레이드\n사용량 추가 구매\n"
                + "현재 세션\n오전 10:00에 재설정\n62% 사용됨\n"
                + "이번 주\n재설정: (토요일) 오후 3:00\n77% 사용됨\n제한 초기화";
        assertEquals(77.0, PageUsage.weeklyPercent(page), 1e-9);
        assertEquals("이번 주", PageUsage.anchor(page));
        // 웹 글자의 줄바꿈 없는 공백(&nbsp;)
        assertEquals(77.0, PageUsage.weeklyPercent("현재 세션\n62%\n이번\u00A0주\n77\u00A0% 사용됨"), 1e-9);
    }

    @Test
    public void koreanPageTakesAllModelsNotSession() {
        String page = "설정\n일반\n계정\n개인정보보호\n결제\n사용량\n사용량 Pro\n"
                + "주의하세요. 이 속도라면 토요일 재설정 전에 사용량이 소진됩니다. 소진 예상 시점: 내일 밤\n"
                + "플랜 업그레이드\n사용량 추가 구매\n"
                + "플랜 사용 한도\n현재 세션\n2시간 10분 후 재설정\n40% 사용됨\n"
                + "주간 한도\n모든 모델\n토 오후 3:00 재설정\n72% 사용됨\n"
                + "Sonnet만\n토 오후 3:00 재설정\n15% 사용됨\n마지막 업데이트: 방금 전";
        assertEquals(72.0, PageUsage.weeklyPercent(page), 1e-9);
    }

    @Test
    public void englishPage() {
        String page = "Plan usage limits\nCurrent session\nResets in 2 hr 10 min\n40% used\n"
                + "Weekly limits\nLearn more about usage limits\nAll models\nResets Sat 3:00 PM\n72% used\n"
                + "Opus only\nResets Sat 3:00 PM\n5% used";
        assertEquals(72.0, PageUsage.weeklyPercent(page), 1e-9);
    }

    @Test
    public void weeklyHeadingWhenNoAllModelsRow() {
        String page = "현재 세션\n12% 사용됨\n주간 한도\n목 오전 4:00 재설정\n35% 사용됨";
        assertEquals(35.0, PageUsage.weeklyPercent(page), 1e-9);
        assertEquals(35.5, PageUsage.weeklyPercent("주간 한도\n35,5 %"), 1e-9);
    }

    @Test
    public void otherWeeklyWordings() {
        assertEquals(64.0, PageUsage.weeklyPercent("현재 세션\n40%\n주별 한도\n64% 사용"), 1e-9);
        assertEquals(64.0, PageUsage.weeklyPercent("현재 세션\n40%\n매주 재설정되는 한도\n64%"), 1e-9);
        assertEquals(64.0, PageUsage.weeklyPercent("현재 세션\n40%\n7일 한도\n64%"), 1e-9);
    }

    @Test
    public void explainsWhatWasSeen() {
        assertEquals("모든 모델", PageUsage.anchor("주간 한도\n모든 모델\n72%"));
        assertEquals("주간", PageUsage.anchor("주간 한도\n72%"));
        assertNull(PageUsage.anchor("현재 세션\n40%"));
        assertNull(PageUsage.anchor(null));
        assertEquals(2, PageUsage.percentCount("40% 사용\n72 % 사용"));
        assertEquals(0, PageUsage.percentCount(null));
    }

    @Test
    public void nothingToTakeWhileLoadingOrWithoutAnchor() {
        assertNull(PageUsage.weeklyPercent(null));
        assertNull(PageUsage.weeklyPercent(""));
        // 아직 막대가 안 그려짐
        assertNull(PageUsage.weeklyPercent("설정\n사용량\n주간 한도\n모든 모델\n불러오는 중"));
        // 기준 글자가 없으면 현재 세션 %를 집지 않음
        assertNull(PageUsage.weeklyPercent("현재 세션\n40% 사용됨"));
        // 100을 넘는 숫자는 사용량이 아님
        assertNull(PageUsage.weeklyPercent("모든 모델\n250%"));
    }

    @Test
    public void sessionFromRealKoreanPage() {
        String page = "사용량\nPro\n플랜 업그레이드\n"
                + "현재 세션\n오전 10:00에 재설정\n62% 사용됨\n"
                + "이번 주\n재설정: (토요일) 오후 3:00\n77% 사용됨\n제한 초기화";
        PageUsage.SessionRead s = session(page);
        assertNotNull(s);
        assertEquals(62.0, s.percent, 1e-9);
        assertEquals(UsageCalcTest.at(10, 3, 10, 0), s.resetAt);
        assertEquals(NOW, s.at);
        // 주간은 그대로
        assertEquals(77.0, PageUsage.weeklyPercent(page), 1e-9);
    }

    @Test
    public void sessionResetAsTimeLeft() {
        PageUsage.SessionRead s = session("플랜 사용 한도\n현재 세션\n2시간 10분 후 재설정\n40% 사용됨\n"
                + "주간 한도\n모든 모델\n토 오후 3:00 재설정\n72% 사용됨");
        assertEquals(40.0, s.percent, 1e-9);
        assertEquals(NOW + 2 * HOUR + 10 * MINUTE, s.resetAt);
        assertEquals(NOW + 45 * MINUTE, session("현재 세션\n45분 후 재설정\n90% 사용됨").resetAt);
        assertEquals(NOW + 3 * HOUR, session("현재 세션\n3시간 후 재설정\n90% 사용됨").resetAt);
    }

    @Test
    public void sessionFromEnglishPage() {
        PageUsage.SessionRead s = session("Plan usage limits\nCurrent session\nResets in 2 hr 10 min\n40% used\n"
                + "Weekly limits\nAll models\nResets Sat 3:00 PM\n72% used");
        assertEquals(40.0, s.percent, 1e-9);
        assertEquals(NOW + 2 * HOUR + 10 * MINUTE, s.resetAt);
        assertEquals(NOW + 45 * MINUTE, session("Current session\nResets in 45 min\n9% used").resetAt);
        assertEquals(UsageCalcTest.at(10, 3, 11, 30),
                session("Current session\nResets 11:30 AM\n9% used").resetAt);
    }

    @Test
    public void sessionResetAfterMidnight() {
        // 밤 11시 반에 "오전 2:00에 재설정" → 다음 날 오전 2시
        long late = UsageCalcTest.at(10, 3, 23, 30);
        PageUsage.SessionRead s = PageUsage.session("현재 세션\n오전 2:00에 재설정\n12% 사용됨", late, SEOUL);
        assertEquals(UsageCalcTest.at(10, 4, 2, 0), s.resetAt);
        // 오후 12시 = 정오
        assertEquals(UsageCalcTest.at(10, 3, 12, 30),
                session("현재 세션\n오후 12:30에 재설정\n12% 사용됨").resetAt);
    }

    @Test
    public void sessionWithoutResetTime() {
        PageUsage.SessionRead s = session("현재 세션\n0% 사용됨\n주간 한도\n35% 사용됨");
        assertEquals(0.0, s.percent, 1e-9);
        assertEquals(0, s.resetAt);
    }

    @Test
    public void sessionNeverTakesWeeklyPercent() {
        // 세션 막대가 아직 안 그려졌을 때 주간 칸의 %를 집지 않음
        assertNull(session("현재 세션\n불러오는 중\n주간 한도\n모든 모델\n72% 사용됨"));
        assertNull(session("주간 한도\n모든 모델\n72% 사용됨"));
        assertNull(session(null));
        assertNull(session(""));
    }
}
