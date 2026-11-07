package com.baomihua.danmaku.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动匹配：拿「关键标题」去搜，搜不到才逐步放宽关键词。
 *
 * <p>对应需求里说的「不要把所有东西都丢给搜索接口」：先用 {@link TitleParser.TitleInfo#key}
 * 这个提炼过的片名搜一次；没结果再把带副标题的、去空格的等写法依次试，最多三轮，命中即停。
 * 每轮的候选都要过 {@link MatchScorer}，季数不符或片名不像就继续找。
 */
public final class AutoMatcher {

    /** 分数低于这个值就认为「不可信」，交给用户手动搜索。 */
    public static final double TRUST_THRESHOLD = 0.45;

    /** 最多尝试几轮关键词。 */
    public static final int MAX_ATTEMPTS = 3;

    public interface Searcher {
        List<Models.Anime> search(String keyword, int episode) throws Exception;
    }

    public static final class Result {
        /** 用来打分的查询信息（series 已经换成真正命中的关键词）。 */
        public final TitleParser.TitleInfo query;
        public final Models.Anime anime;
        public final Models.Episode episode;
        public final double score;
        /** 命中的关键词。 */
        public final String keyword;
        /** 实际尝试了几轮。 */
        public final int attempts;
        /** 尝试过的关键词，便于界面提示。 */
        public final List<String> tried;

        Result(TitleParser.TitleInfo query, Models.Anime anime, Models.Episode episode,
               double score, String keyword, int attempts, List<String> tried) {
            this.query = query;
            this.anime = anime;
            this.episode = episode;
            this.score = score;
            this.keyword = keyword;
            this.attempts = attempts;
            this.tried = tried;
        }

        public boolean trusted() {
            return score >= TRUST_THRESHOLD;
        }

        @Override
        public String toString() {
            return String.format("%.2f %s -> %s / %s（%d 轮）", score, keyword, anime.title,
                    episode.title, attempts);
        }
    }

    private AutoMatcher() {
    }

    /**
     * @return 最好的结果；全部搜不到时返回 null（此时 best 一定也是 null）。
     */
    public static Result find(TitleParser.TitleInfo info, Searcher searcher) throws Exception {
        List<String> queries = info.searchQueries();
        if (queries.isEmpty()) {
            return null;
        }
        List<String> tried = new ArrayList<>();
        Result best = null;
        int limit = Math.min(MAX_ATTEMPTS, queries.size());
        for (int attempt = 0; attempt < limit; attempt++) {
            String keyword = queries.get(attempt);
            tried.add(keyword);
            List<Models.Anime> animes = searcher.search(keyword, info.episode);
            if (animes == null || animes.isEmpty()) {
                continue;
            }
            List<MatchScorer.Candidate> ranked = MatchScorer.rank(info.withSeries(keyword), animes);
            if (ranked.isEmpty()) {
                continue;
            }
            MatchScorer.Candidate top = ranked.get(0);
            Result result = new Result(info.withSeries(keyword), top.anime, top.episode,
                    top.score, keyword, attempt + 1, new ArrayList<>(tried));
            if (top.score >= TRUST_THRESHOLD) {
                return result;
            }
            if (best == null || top.score > best.score) {
                best = result;
            }
        }
        return best;
    }

    /**
     * 多季剧：给定作品列表，挑出和查询季数一致的那一部。
     * 用于「其他季弹幕更多也不要乱选」的场景。
     */
    public static Models.Anime pickSeason(List<Models.Anime> animes, int season) {
        if (animes == null || animes.isEmpty()) {
            return null;
        }
        if (season <= 0) {
            return animes.get(0);
        }
        Models.Anime exact = null;
        Models.Anime unknown = null;
        for (Models.Anime anime : animes) {
            int candidate = MatchScorer.seasonOf(anime.title);
            if (candidate == season) {
                exact = anime;
                break;
            }
            if (candidate == 0 && unknown == null) {
                unknown = anime;
            }
        }
        return exact != null ? exact : (unknown != null ? unknown : animes.get(0));
    }
}
