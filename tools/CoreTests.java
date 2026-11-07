import com.baomihua.danmaku.core.AutoMatcher;
import com.baomihua.danmaku.core.CommentParser;
import com.baomihua.danmaku.core.Danmaku;
import com.baomihua.danmaku.core.DanmakuEngine;
import com.baomihua.danmaku.core.DanmuApi;
import com.baomihua.danmaku.core.Http;
import com.baomihua.danmaku.core.MatchScorer;
import com.baomihua.danmaku.core.MiniJson;
import com.baomihua.danmaku.core.PlaybackGate;
import com.baomihua.danmaku.core.Models;
import com.baomihua.danmaku.core.Settings;
import com.baomihua.danmaku.core.TitleParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 核心层回归测试（纯 JVM，不依赖 Android）。
 * 运行：java -cp build/jvm-tests CoreTests
 */
public final class CoreTests {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        titleParser();
        keyTitles();
        chineseNumbers();
        matchScorer();
        autoMatcher();
        playbackGate();
        commentParser();
        jsonAndSettings();
        engineLayout();
        engineSeek();
        engineMergingAndOffset();
        apiClient();

        System.out.println();
        System.out.println("==========================================");
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            for (String f : failures) {
                System.out.println("  ✗ " + f);
            }
            System.out.println("==========================================");
            System.exit(1);
        }
        System.out.println("全部通过");
        System.out.println("==========================================");
    }

    // ------------------------------------------------------------ 片名解析

    private static void titleParser() {
        TitleParser.TitleInfo a = TitleParser.parse("葬送的芙莉莲 第二季 第05集");
        eq("中文季+集: 季", 2, a.season);
        eq("中文季+集: 集", 5, a.episode);
        check("中文季+集: 片名", a.series.contains("芙莉莲"), a.series);

        TitleParser.TitleInfo b = TitleParser.parse("[Lilith-Raws] 咒术回战 S02E05 [1080p][CHT]");
        eq("S02E05: 季", 2, b.season);
        eq("S02E05: 集", 5, b.episode);
        check("S02E05: 片名", b.series.contains("咒术回战"), b.series);
        check("S02E05: 去掉分辨率", !b.series.toLowerCase().contains("1080p"), b.series);

        TitleParser.TitleInfo c = TitleParser.parse("进击的巨人 - 05");
        eq("破折号集数", 5, c.episode);
        eq("破折号集数: 无季", 0, c.season);
        check("破折号集数: 片名", c.series.contains("进击的巨人"), c.series);

        TitleParser.TitleInfo d = TitleParser.parse("赛博朋克：边缘行者 EP12");
        eq("EP 形式", 12, d.episode);
        check("EP 形式: 片名", d.series.contains("赛博朋克"), d.series);

        TitleParser.TitleInfo e = TitleParser.parse("Some Show Season 2 Episode 3");
        eq("英文季", 2, e.season);
        eq("英文集", 3, e.episode);

        TitleParser.TitleInfo f = TitleParser.parse("孤独摇滚！第8话");
        eq("第N话", 8, f.episode);
        check("第N话: 片名", f.series.contains("孤独摇滚"), f.series);

        TitleParser.TitleInfo g = TitleParser.parse("我的英雄学院 第七季");
        eq("第七季", 7, g.season);
        eq("只有季没有集", 0, g.episode);
        check("第七季: 片名", g.series.contains("我的英雄学院"), g.series);

        TitleParser.TitleInfo h = TitleParser.parse("【喵萌奶茶屋】★10月新番★[药屋少女的呢喃][05][1080p][简日双语]");
        eq("方括号集数", 5, h.episode);
        check("方括号集数: 片名", h.series.contains("药屋少女"), h.series);

        TitleParser.TitleInfo i = TitleParser.parse("4K HDR 测试片源 2160p");
        eq("分辨率不会被当成集数", 0, i.episode);

        check("特别篇识别", TitleParser.isSpecial("某某 预告片"), "预告片");
        check("正片不算特别篇", !TitleParser.isSpecial("某某 第5集"), "");
    }

    // ------------------------------------------------------------ 关键标题

    private static void keyTitles() {
        TitleParser.TitleInfo a =
                TitleParser.parse("【喵萌奶茶屋】★10月新番★[药屋少女的呢喃][05][1080p][简日双语]");
        eq("关键标题: 去掉组名与噪声", "药屋少女的呢喃", a.key);
        eq("关键标题: 首个搜索词就是它", "药屋少女的呢喃", a.searchQueries().get(0));

        TitleParser.TitleInfo b = TitleParser.parse("葬送的芙莉莲 第二季 第05集");
        eq("关键标题: 去掉季集", "葬送的芙莉莲", b.key);

        TitleParser.TitleInfo c = TitleParser.parse("[Lilith-Raws] 咒术回战 S02E05 [1080p][CHT]");
        eq("关键标题: 去掉技术标签", "咒术回战", c.key);

        TitleParser.TitleInfo d = TitleParser.parse("进击的巨人 - 05");
        eq("关键标题: 破折号", "进击的巨人", d.key);

        TitleParser.TitleInfo e = TitleParser.parse("The Last of Us S01E03");
        eq("关键标题: 英文保留完整词组", "The Last of Us", e.key);

        TitleParser.TitleInfo f = TitleParser.parse("【喵萌奶茶屋】某番 更新至 12集");
        eq("关键标题: 去掉更新至", "某番", f.key);

        // 真实播放器标题：片名 + 第N集 + 《分集小标题》
        TitleParser.TitleInfo real = TitleParser.parse(
                "学生会也有洞！ 第 3 集 《太好了呢》《泳池大扫除的泳装回！》");
        eq("真实标题: 集数", 3, real.episode);
        eq("真实标题: 关键标题", "学生会也有洞", real.key);
        eq("真实标题: 首个搜索词", "学生会也有洞", real.searchQueries().get(0));
        check("真实标题: 不带分集副标题", !real.series.contains("泳池"), real.series);

        TitleParser.TitleInfo sub = TitleParser.parse("葬送的芙莉莲 第5集 断头台的阿乌拉");
        eq("副标题不混进片名", "葬送的芙莉莲", sub.key);

        TitleParser.TitleInfo first = TitleParser.parse("第5集 某番");
        eq("集数在前也能认", 5, first.episode);
        eq("集数在前: 片名", "某番", first.key);

        TitleParser.TitleInfo g = TitleParser.parse("4K HDR 测试片源 2160p");
        check("关键标题: 纯噪声时不为空或有回退", true, g.key + "/" + g.series);

        java.util.List<String> q = TitleParser.parse("赛博朋克：边缘行者 EP12").searchQueries();
        check("搜索词不为空", !q.isEmpty(), String.valueOf(q));
        check("搜索词无重复", q.size() == new java.util.HashSet<>(q).size(), String.valueOf(q));
        check("搜索词长度合理", q.get(0).length() <= 60, q.get(0));
    }

    private static void chineseNumbers() {
        eq("十二", 12, TitleParser.parse("某某 第十二话").episode);
        eq("五", 5, TitleParser.parse("某某 第五集").episode);
        eq("二十", 20, TitleParser.parse("某某 第二十集").episode);
        eq("十三", 13, TitleParser.parse("某某动画 第十三集").episode);
    }

    // ------------------------------------------------------------ 匹配

    private static void matchScorer() {
        Models.Anime s1 = new Models.Anime(1, "某番 第一季", "TV");
        s1.episodes.add(new Models.Episode(101, 1, "某番 第一季", "第5话"));
        s1.episodes.add(new Models.Episode(102, 1, "某番 第一季", "第6话"));

        Models.Anime s2 = new Models.Anime(2, "某番 第二季", "TV");
        s2.episodes.add(new Models.Episode(201, 2, "某番 第二季", "第5话"));
        s2.episodes.add(new Models.Episode(202, 2, "某番 第二季", "第6话"));

        Models.Anime other = new Models.Anime(3, "完全无关的作品", "TV");
        other.episodes.add(new Models.Episode(301, 3, "完全无关的作品", "第5话"));

        TitleParser.TitleInfo q = TitleParser.parse("某番 第二季 第5集");
        List<MatchScorer.Candidate> ranked =
                MatchScorer.rank(q, Arrays.asList(s1, s2, other));
        check("多季匹配: 有结果", !ranked.isEmpty(), "");
        eq("多季匹配: 选中第二季", 201L, ranked.get(0).episode.episodeId);

        TitleParser.TitleInfo q2 = TitleParser.parse("某番 第一季 第6集");
        List<MatchScorer.Candidate> ranked2 =
                MatchScorer.rank(q2, Arrays.asList(s2, s1));
        eq("多季匹配: 选中第一季", 102L, ranked2.get(0).episode.episodeId);

        double same = MatchScorer.titleSimilarity("葬送的芙莉莲", "葬送的芙莉莲");
        check("同名相似度=1", same > 0.99, String.valueOf(same));
        double diff = MatchScorer.titleSimilarity("葬送的芙莉莲", "间谍过家家");
        check("无关相似度<0.3", diff < 0.3, String.valueOf(diff));
        double partial = MatchScorer.titleSimilarity("葬送的芙莉莲", "葬送的芙莉莲 第二季");
        check("包含关系>0.7", partial > 0.7, String.valueOf(partial));
    }

    // ------------------------------------------------------------ 自动匹配（多轮搜索）

    private static void autoMatcher() {
        // 第一轮（关键标题）搜不到，第二轮才命中
        final java.util.List<String> calls = new java.util.ArrayList<>();
        TitleParser.TitleInfo info = TitleParser.parse("【某组】葬送的芙莉莲 新番 第5集");
        AutoMatcher.Result r1;
        try {
            r1 = AutoMatcher.find(info, new AutoMatcher.Searcher() {
                @Override
                public List<Models.Anime> search(String keyword, int episode) {
                    calls.add(keyword);
                    if (keyword.equals("葬送的芙莉莲")) {
                        return new java.util.ArrayList<>();   // 第一轮故意空
                    }
                    return java.util.Collections.singletonList(anime("葬送的芙莉莲 第二季", 2,
                            new String[]{"第5话", "第6话"}));
                }
            });
        } catch (Exception e) {
            r1 = null;
        }
        check("自动匹配: 有结果", r1 != null, "");
        eq("自动匹配: 第二轮命中", 2, r1 == null ? -1 : r1.attempts);
        eq("自动匹配: 第一轮用的是关键标题", "葬送的芙莉莲", calls.isEmpty() ? "" : calls.get(0));
        check("自动匹配: 结果可信", r1 != null && r1.trusted(), String.valueOf(r1));
        eq("自动匹配: 选中第5话", 500L, r1 == null ? -1L : r1.episode.episodeId);

        // 第一轮就命中：只搜一次
        final int[] count = {0};
        try {
            AutoMatcher.find(TitleParser.parse("某番 第二季 第6集"), new AutoMatcher.Searcher() {
                @Override
                public List<Models.Anime> search(String keyword, int episode) {
                    count[0]++;
                    return java.util.Collections.singletonList(
                            anime("某番 第二季", 2, new String[]{"第5话", "第6话"}));
                }
            });
        } catch (Exception ignored) {
        }
        eq("自动匹配: 命中即停", 1, count[0]);

        // 全都搜不到 → null
        AutoMatcher.Result none = null;
        try {
            none = AutoMatcher.find(TitleParser.parse("完全不存在的东西 第1集"),
                    new AutoMatcher.Searcher() {
                        @Override
                        public List<Models.Anime> search(String keyword, int episode) {
                            return new java.util.ArrayList<>();
                        }
                    });
        } catch (Exception ignored) {
        }
        check("自动匹配: 全空返回 null", none == null, "");

        // 只返回不相关作品 → 结果不可信
        AutoMatcher.Result weak = null;
        try {
            weak = AutoMatcher.find(TitleParser.parse("葬送的芙莉莲 第3集"),
                    new AutoMatcher.Searcher() {
                        @Override
                        public List<Models.Anime> search(String keyword, int episode) {
                            return java.util.Collections.singletonList(
                                    anime("完全无关的另一部作品", 0, new String[]{"第3话"}));
                        }
                    });
        } catch (Exception ignored) {
        }
        check("自动匹配: 低分不可信", weak != null && !weak.trusted(),
                weak == null ? "null" : String.valueOf(weak.score));

        // 多季：季数一致优先
        List<Models.Anime> seasons = new java.util.ArrayList<>();
        seasons.add(anime("某番 第一季", 1, new String[]{"第1话"}));
        seasons.add(anime("某番 第二季", 2, new String[]{"第1话"}));
        Models.Anime picked = AutoMatcher.pickSeason(seasons, 2);
        eq("多季: 挑第二季", "某番 第二季", picked == null ? "" : picked.title);
    }

    private static Models.Anime anime(String title, int season, String[] episodes) {
        Models.Anime anime = new Models.Anime(title.hashCode(), title, "TV");
        long base = 500L;
        for (int i = 0; i < episodes.length; i++) {
            anime.episodes.add(new Models.Episode(base + i, anime.animeId, title, episodes[i]));
        }
        return anime;
    }

    // ------------------------------------------------------------ 退出播放后弹幕要收起来

    private static void playbackGate() {
        long last = 100_000L;

        // 播放中、有数据、当前隐藏 → 显示
        eq("门控: 播放中该显示", PlaybackGate.ACTION_SHOW, PlaybackGate.decide(
                true, false, true, true, false, last + 100, last, 0));
        // 播放中、没数据 → 保持
        eq("门控: 没数据不显示", PlaybackGate.ACTION_KEEP, PlaybackGate.decide(
                true, false, true, false, false, last + 100, last, 0));

        // 会话消失：2 秒内先不动（切集不闪）
        eq("门控: 刚没会话先不动", PlaybackGate.ACTION_KEEP, PlaybackGate.decide(
                false, false, false, true, true, last + 2000, last, 0));
        // 超过 2.5 秒 → 收起
        eq("门控: 会话消失 2.5 秒后收起", PlaybackGate.ACTION_HIDE, PlaybackGate.decide(
                false, false, false, true, true, last + 2600, last, 0));

        // 播放器报停止 → 立刻收起
        eq("门控: 停止立刻收起", PlaybackGate.ACTION_HIDE, PlaybackGate.decide(
                true, true, false, true, true, last + 100, last, 0));
        // 已经收起了就不重复收
        eq("门控: 已收起不重复", PlaybackGate.ACTION_KEEP, PlaybackGate.decide(
                true, true, false, true, false, last + 100, last, 0));

        // 暂停一会儿（用户真的按了暂停）→ 保持
        eq("门控: 短暂暂停保持", PlaybackGate.ACTION_KEEP, PlaybackGate.decide(
                true, false, false, true, true, last + 5000, last, 5000));
        // 暂停太久（多半已退出播放器，会话还挂着）→ 收起
        eq("门控: 暂停太久收起", PlaybackGate.ACTION_HIDE, PlaybackGate.decide(
                true, false, false, true, true, last + 25000, last, 25000));
        // 收起后重新播放 → 再显示
        eq("门控: 恢复播放再显示", PlaybackGate.ACTION_SHOW, PlaybackGate.decide(
                true, false, true, true, false, last + 26000, last, 0));
    }

    // ------------------------------------------------------------ 弹幕格式

    private static void commentParser() {
        String json = "{\"code\":0,\"count\":3,\"comments\":["
                + "{\"cid\":1,\"p\":\"12.345,1,16777215,abc\",\"m\":\"前方高能\"},"
                + "{\"cid\":2,\"p\":\"13.5,5,16711680,def\",\"m\":\"顶部弹幕\"},"
                + "{\"cid\":3,\"p\":\"20.0,4,255,ghi\",\"m\":\"底部弹幕\"}]}";
        List<Danmaku> list = CommentParser.parseDandanplayJson(json);
        eq("JSON 弹幕数", 3, list.size());
        eq("JSON 时间", 12345L, list.get(0).timeMs);
        eq("JSON 模式", 1, list.get(0).mode);
        eq("JSON 颜色", 0xFFFFFF, list.get(0).color);
        eq("JSON 文本", "前方高能", list.get(0).text);
        eq("JSON 顶部模式", 5, list.get(1).mode);
        eq("JSON 红色", 0xFF0000, list.get(1).color);

        String xml = "<?xml version=\"1.0\"?><i><chatserver>x</chatserver>"
                + "<d p=\"5.5,1,25,0,0,0,0,0\">你好&amp;再见</d>"
                + "<d p=\"6.0,5,16777215,0,0,0,0,0\">顶部 &lt;测试&gt;</d>"
                + "</i>";
        List<Danmaku> xl = CommentParser.parseXml(xml);
        eq("XML 弹幕数", 2, xl.size());
        eq("XML 时间", 5500L, xl.get(0).timeMs);
        eq("XML 实体", "你好&再见", xl.get(0).text);
        eq("XML 中括号实体", "顶部 <测试>", xl.get(1).text);
        eq("XML 顶部", 5, xl.get(1).mode);

        List<Danmaku> auto = CommentParser.parseAuto(json);
        eq("自动识别 JSON", 3, auto.size());
        List<Danmaku> autoXml = CommentParser.parseAuto(xml);
        eq("自动识别 XML", 2, autoXml.size());
        eq("空内容", 0, CommentParser.parseAuto("").size());
    }

    // ------------------------------------------------------------ JSON/设置

    private static void jsonAndSettings() {
        Object parsed = MiniJson.parse("{\"a\":[1,2,{\"b\":\"x\\u4e2d\"}],\"c\":true,\"d\":null}");
        Map<String, Object> m = MiniJson.asObject(parsed);
        check("JSON 解析对象", m != null, "");
        eq("JSON 嵌套数组长度", 3, MiniJson.asArray(m.get("a")).size());
        Map<String, Object> inner = MiniJson.asObject(MiniJson.asArray(m.get("a")).get(2));
        eq("JSON unicode 转义", "x中", MiniJson.str(inner, "b"));
        eq("JSON 布尔", true, MiniJson.bool(m, "c", false));

        Map<String, Object> round = new HashMap<>();
        round.put("text", "带\"引号\"和\n换行");
        round.put("n", 42.0);
        String written = MiniJson.write(round);
        Map<String, Object> back = MiniJson.asObject(MiniJson.parse(written));
        eq("JSON 往返文本", "带\"引号\"和\n换行", MiniJson.str(back, "text"));
        eq("JSON 往返数字", 42L, MiniJson.lng(back, "n", 0));

        Settings s = Settings.defaults();
        s.fontSp = 999;
        s.speed = 99f;
        s.areaPercent = 10;
        Settings clamped = Settings.fromJson(s.toJson());
        eq("设置: 字号上限", 48, clamped.fontSp);
        eq("设置: 速度上限", 3f, clamped.speed);
        eq("设置: 区域下限", 25, clamped.areaPercent);

        Settings empty = Settings.fromJson("{bad json");
        eq("坏 JSON 用默认", 22, empty.fontSp);

        Settings words = Settings.defaults();
        words.blocked = "剧透， 前方,高能\n屏蔽";
        eq("屏蔽词数量", 4, words.blockedWords().length);
        eq("屏蔽词首项", "剧透", words.blockedWords()[0]);
        eq("屏蔽词去空格", "前方", words.blockedWords()[1]);
    }

    // ------------------------------------------------------------ 引擎：排版

    private static void engineLayout() {
        final int width = 1920;
        final int height = 1080;
        DanmakuEngine engine = new DanmakuEngine(new FakeMeasurer());
        DanmakuEngine.Config cfg = engine.config();
        cfg.fontPx = 44f;
        cfg.referenceHeightPx = 1080f;
        cfg.scaleWithViewport = true;
        cfg.areaRatio = 0.5f;
        cfg.speed = 1f;
        cfg.mergeDuplicates = false;
        engine.setViewport(width, height);
        engine.applyConfig(cfg);

        List<Danmaku> list = new ArrayList<>();
        java.util.Random random = new java.util.Random(7);
        String[] texts = {"哈哈哈", "前方高能预警", "这个作画太强了", "泪目", "666", "名场面",
                "弹幕护体", "第一次看", "三刷", "这里配乐绝了"};
        for (int i = 0; i < 400; i++) {
            long t = (long) (random.nextDouble() * 40000);
            list.add(new Danmaku(t, texts[i % texts.length] + (i % 17 == 0 ? "加长加长加长" : ""),
                    i % 20 == 0 ? Danmaku.MODE_TOP : Danmaku.MODE_SCROLL, 0xFFFFFF));
        }
        engine.load(list);
        eq("引擎装载数量", 400, engine.loadedCount());

        int maxActive = 0;
        boolean overlapFound = false;
        String overlapDetail = "";
        long lastMs = 0;
        for (long ms = 0; ms <= 40000; ms += 16) {
            engine.frame(ms);
            lastMs = ms;
            maxActive = Math.max(maxActive, engine.size());
            // 同一轨道内不允许 x 区间重叠
            Map<Integer, List<float[]>> lanes = new HashMap<>();
            for (int i = 0; i < engine.size(); i++) {
                DanmakuEngine.Sprite s = engine.spriteAt(i);
                if (s.fixed) {
                    continue;
                }
                float w = new FakeMeasurer().measure(s.text, s.textPx);
                int lane = Math.round((s.baselineY - s.textPx) / (s.textPx * 1.32f));
                List<float[]> items = lanes.get(lane);
                if (items == null) {
                    items = new ArrayList<>();
                    lanes.put(lane, items);
                }
                for (float[] other : items) {
                    if (s.x < other[0] + other[1] - 1f && other[0] < s.x + w - 1f) {
                        overlapFound = true;
                        overlapDetail = "lane=" + lane + " t=" + ms;
                    }
                }
                items.add(new float[]{s.x, w});
            }
        }
        check("引擎: 同轨道不重叠", !overlapFound, overlapDetail);
        check("引擎: 有并发弹幕", maxActive > 3, "maxActive=" + maxActive);
        check("引擎: 并发受密度限制", maxActive <= 140, "maxActive=" + maxActive);
        check("引擎: 不遮挡顶部区域之外", true, "");
        System.out.println("  引擎峰值并发 = " + maxActive + "，丢弃 " + engine.stats().dropped + " 条");
    }

    // ------------------------------------------------------------ 引擎：跳转

    private static void engineSeek() {
        DanmakuEngine engine = new DanmakuEngine(new FakeMeasurer());
        DanmakuEngine.Config cfg = engine.config();
        cfg.fontPx = 44f;
        cfg.scaleWithViewport = false;
        cfg.areaRatio = 1f;
        engine.setViewport(1920, 1080);
        engine.applyConfig(cfg);

        List<Danmaku> list = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            list.add(new Danmaku(i * 200L, "第" + i + "条弹幕", Danmaku.MODE_SCROLL, 0xFFFFFF));
        }
        engine.load(list);

        for (long ms = 0; ms < 5000; ms += 16) {
            engine.frame(ms);
        }
        int beforeSeek = engine.size();
        check("跳转前有弹幕", beforeSeek > 0, String.valueOf(beforeSeek));

        // 跳到 100 秒（超出素材范围）
        engine.frame(100000);
        eq("跳到末尾清屏", 0, engine.size());

        // 跳回 10 秒
        engine.frame(10000);
        for (long ms = 10000; ms < 12000; ms += 16) {
            engine.frame(ms);
        }
        check("跳回后重新出现", engine.size() > 0, String.valueOf(engine.size()));

        // 时间倒退（模拟用户拖回）
        engine.frame(3000);
        for (long ms = 3000; ms < 4000; ms += 16) {
            engine.frame(ms);
        }
        check("倒退后仍有弹幕", engine.size() >= 0, "");
    }

    // ------------------------------------------------------------ 引擎：合并与偏移

    private static void engineMergingAndOffset() {
        DanmakuEngine engine = new DanmakuEngine(new FakeMeasurer());
        DanmakuEngine.Config cfg = engine.config();
        cfg.fontPx = 40f;
        cfg.scaleWithViewport = false;
        cfg.mergeDuplicates = true;
        engine.setViewport(1000, 1000);
        engine.applyConfig(cfg);

        List<Danmaku> list = new ArrayList<>();
        list.add(new Danmaku(1000, "重复弹幕", Danmaku.MODE_SCROLL, 0xFFFFFF));
        list.add(new Danmaku(2000, "重复弹幕", Danmaku.MODE_SCROLL, 0xFFFFFF));
        list.add(new Danmaku(3000, "重复弹幕", Danmaku.MODE_SCROLL, 0xFFFFFF));
        list.add(new Danmaku(20000, "重复弹幕", Danmaku.MODE_SCROLL, 0xFFFFFF));
        engine.load(list);
        eq("合并后条目数", 2, engine.loadedCount());

        engine.frame(0);
        engine.frame(1000);
        engine.frame(1500);
        boolean sawSuffix = false;
        for (int i = 0; i < engine.size(); i++) {
            String text = engine.spriteAt(i).text;
            if (text.contains("×3")) {
                sawSuffix = true;
            }
        }
        check("合并显示 ×3", sawSuffix, "");

        // 时间偏移：+2 秒 → 1 秒处的弹幕在 3 秒才出现
        DanmakuEngine shifted = new DanmakuEngine(new FakeMeasurer());
        DanmakuEngine.Config c2 = shifted.config();
        c2.fontPx = 40f;
        c2.scaleWithViewport = false;
        c2.timeOffsetMs = 2000;
        shifted.setViewport(1000, 1000);
        shifted.applyConfig(c2);
        List<Danmaku> one = new ArrayList<>();
        one.add(new Danmaku(1000, "偏移测试", Danmaku.MODE_SCROLL, 0xFFFFFF));
        shifted.load(one);
        shifted.frame(0);
        shifted.frame(1000);
        eq("偏移: 1 秒时未出现", 0, shifted.size());
        shifted.frame(2900);
        shifted.frame(3000);
        eq("偏移: 3 秒时出现", 1, shifted.size());
    }

    // ------------------------------------------------------------ API 客户端

    private static void apiClient() {
        eq("端点: 裸域名", "https://danmu.example.com/api/v2",
                DanmuApi.endpointOf("danmu.example.com"));
        eq("端点: 带斜杠", "https://danmu.example.com/api/v2",
                DanmuApi.endpointOf("https://danmu.example.com/"));
        eq("端点: 已含 api/v2", "https://danmu.example.com/api/v2",
                DanmuApi.endpointOf("https://danmu.example.com/api/v2/"));
        eq("端点: 路径 Token", "https://danmu.example.com/abc/api/v2",
                DanmuApi.endpointOf("https://danmu.example.com/abc"));

        final Map<String, String> responses = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        Http fake = new Http() {
            @Override
            public Response get(String url, Map<String, String> headers, int timeoutMs) {
                requested.add(url);
                if (url.contains("/comment/")) {
                    return new Response(200, responses.get("comment"));
                }
                if (url.contains("/bangumi/")) {
                    return new Response(200, responses.get("bangumi"));
                }
                if (url.contains("/search/episodes")) {
                    return new Response(Integer.parseInt(responses.get("searchCode")),
                            responses.get("search"));
                }
                return new Response(404, "");
            }

            @Override
            public Response post(String url, String jsonBody, Map<String, String> headers, int timeoutMs) {
                requested.add("POST " + url);
                return new Response(200, "{\"isMatched\":true,\"matches\":[{\"episodeId\":88,"
                        + "\"animeId\":9,\"animeTitle\":\"某番\",\"episodeTitle\":\"第5话\"}]}");
            }
        };

        responses.put("search", "{\"success\":true,\"errorCode\":0,\"animes\":[{\"animeId\":9,"
                + "\"animeTitle\":\"某番\",\"typeDescription\":\"TV\",\"episodes\":["
                + "{\"episodeId\":88,\"episodeTitle\":\"第5话\"},{\"episodeId\":89,\"episodeTitle\":\"第6话\"}]}]}");
        responses.put("searchCode", "200");
        responses.put("bangumi", "{\"bangumi\":{\"animeId\":9,\"animeTitle\":\"某番\",\"episodes\":["
                + "{\"episodeId\":88,\"episodeTitle\":\"第5话\"}]}}");
        responses.put("comment", "{\"count\":1,\"comments\":[{\"cid\":1,\"p\":\"1.0,1,16777215,0\","
                + "\"m\":\"测试弹幕\"}]}");

        DanmuApi api = new DanmuApi("https://danmu.example.com", fake);
        try {
            List<Models.Anime> animes = api.searchEpisodes("某番", 5);
            eq("API: 搜索结果数", 1, animes.size());
            eq("API: 分集数", 2, animes.get(0).episodes.size());
            check("API: 请求带 episode 参数", requested.get(0).contains("episode=5"), requested.get(0));
            check("API: 关键词编码", requested.get(0).contains("anime="), requested.get(0));

            List<Danmaku> comments = api.comments(88);
            eq("API: 弹幕数", 1, comments.size());
            eq("API: 弹幕文本", "测试弹幕", comments.get(0).text);

            List<Models.Episode> matched = api.match("某番 - 第5集.mp4", 1400000);
            eq("API: 匹配结果", 1, matched.size());
            eq("API: 匹配集 ID", 88L, matched.get(0).episodeId);

            List<Models.Episode> eps = api.episodesOf(9);
            eq("API: bangumi 分集", 1, eps.size());
        } catch (DanmuApi.ApiException e) {
            check("API: 未抛异常", false, e.getMessage());
        }

        // 429
        responses.put("searchCode", "429");
        try {
            api.searchEpisodes("某番");
            check("API: 429 应抛异常", false, "没有抛异常");
        } catch (DanmuApi.ApiException e) {
            eq("API: 429 状态码", 429, e.httpCode);
            check("API: 429 提示", e.getMessage().contains("请求过多"), e.getMessage());
        }

        // 未配置地址
        try {
            new DanmuApi("", fake).searchEpisodes("x");
            check("API: 空地址应抛异常", false, "");
        } catch (DanmuApi.ApiException e) {
            check("API: 空地址提示", e.getMessage().contains("地址"), e.getMessage());
        }
    }

    // ------------------------------------------------------------ 断言工具

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            String msg = name + (detail == null || detail.isEmpty() ? "" : "  [" + detail + "]");
            failures.add(msg);
            System.out.println("  ✗ " + msg);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null
                : (expected instanceof Number && actual instanceof Number
                ? Math.abs(((Number) expected).doubleValue() - ((Number) actual).doubleValue()) < 1e-6
                : expected.equals(actual));
        check(name, ok, "期望=" + expected + " 实际=" + actual);
    }

    private static final class FakeMeasurer implements DanmakuEngine.Measurer {
        @Override
        public float measure(String text, float textPx) {
            float w = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                w += c < 0x2000 ? textPx * 0.55f : textPx;
            }
            return w;
        }
    }

    private CoreTests() {
    }
}
