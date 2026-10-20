package com.hdlee.pdfnote;

import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.Candidate;
import com.google.mlkit.genai.prompt.GenerateContentRequest;
import com.google.mlkit.genai.prompt.GenerateContentResponse;
import com.google.mlkit.genai.prompt.Generation;
import com.google.mlkit.genai.prompt.TextPart;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * On-device AI (Gemini Nano through the ML Kit GenAI Prompt API): summary, title and tag suggestions,
 * proofreading and translation. Everything runs on the phone; nothing is sent to a server.
 * All methods block, so call them from a background thread.
 */
final class AiAssistant {
    private AiAssistant() {}

    /** Gemini Nano takes under about 4000 tokens per request, so long texts are cut into pieces first. */
    static final int SUMMARY_CHUNK = 2200, EDIT_CHUNK = 900, MAX_INPUT_CHARS = 60000;

    /** Thrown when the user pressed 취소 while a long job was running. */
    static final class Canceled extends Exception { Canceled() { super("취소했습니다"); } }

    interface Progress { void update(int done, int total); }

    /** Target languages for 번역: label shown to the user, name used in the prompt, short code for the file name. */
    static final String[] LANGUAGES = {"한국어", "영어", "일본어", "중국어(간체)", "스페인어", "프랑스어", "독일어"};
    static final String[] LANGUAGE_NAMES = {"Korean", "English", "Japanese", "Simplified Chinese", "Spanish", "French", "German"};

    private static GenerativeModelFutures model;
    private static synchronized GenerativeModelFutures model() {
        if (model == null) model = GenerativeModelFutures.from(Generation.INSTANCE.getClient());
        return model;
    }

    /** One of {@link FeatureStatus}: AVAILABLE, DOWNLOADABLE, DOWNLOADING or UNAVAILABLE (also when the check itself fails). */
    static int status() {
        try { return model().checkStatus().get(10, TimeUnit.SECONDS); }
        catch (Throwable error) { return FeatureStatus.UNAVAILABLE; }
    }

    static boolean supported() { return status() != FeatureStatus.UNAVAILABLE; }

    /** Makes the model ready: downloads Gemini Nano first when this phone has not got it yet. Returns an error message, or null when ready. */
    static String prepare(Progress downloadProgress) {
        try {
            int status = status();
            if (status == FeatureStatus.AVAILABLE) return null;
            if (status == FeatureStatus.UNAVAILABLE) return "이 기기는 Gemini Nano(온디바이스 AI)를 지원하지 않습니다";
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<String> failure = new AtomicReference<>();
            if (status == FeatureStatus.DOWNLOADABLE) {
                model().download(new DownloadCallback() {
                    @Override public void onDownloadStarted(long bytesToDownload) { }
                    @Override public void onDownloadProgress(long totalBytesDownloaded) { if (downloadProgress != null) downloadProgress.update((int) Math.min(Integer.MAX_VALUE, totalBytesDownloaded >> 10), 0); }
                    @Override public void onDownloadCompleted() { done.countDown(); }
                    @Override public void onDownloadFailed(GenAiException error) { failure.set("AI 모델을 내려받지 못했습니다: " + error.getMessage()); done.countDown(); }
                });
            } else done.countDown();
            if (!done.await(30, TimeUnit.MINUTES)) return "AI 모델 내려받기가 끝나지 않았습니다. 잠시 뒤 다시 시도하세요";
            if (failure.get() != null) return failure.get();
            return status() == FeatureStatus.AVAILABLE ? null : "AI 모델을 아직 사용할 수 없습니다. 내려받기가 끝난 뒤 다시 시도하세요";
        } catch (Throwable error) {
            return "AI를 준비하지 못했습니다: " + error.getMessage();
        }
    }

    /** One request to the model. The text goes inside markers so the model treats it as material, not as instructions. */
    private static String ask(String instruction, String material, int maxOutputTokens, AtomicBoolean canceled) throws Exception {
        if (canceled != null && canceled.get()) throw new Canceled();
        String prompt = instruction + "\n\n<<<\n" + material + "\n>>>";
        GenerateContentRequest.Builder request = new GenerateContentRequest.Builder(new TextPart(prompt));
        request.setTemperature(.2f);
        request.setMaxOutputTokens(maxOutputTokens);
        GenerateContentResponse response = model().generateContent(request.build()).get(3, TimeUnit.MINUTES);
        List<Candidate> candidates = response.getCandidates();
        if (candidates == null || candidates.isEmpty()) return "";
        String text = candidates.get(0).getText();
        return text == null ? "" : clean(text);
    }

    /** Drops chat-style lead-ins and code fences models like to add. */
    static String clean(String text) {
        String t = text.trim();
        t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        t = t.replaceFirst("^(?i)(here is|here's|sure|물론|다음은)[^\\n]*:\\s*\\n+", "");
        t = t.replaceAll("^<<<\\s*", "").replaceAll("\\s*>>>$", "");
        return t.trim();
    }

    /** Cuts [text] into pieces of at most [limit] characters, at line breaks or sentence ends where possible. */
    static List<String> chunks(String text, int limit) {
        List<String> out = new ArrayList<>();
        String rest = text.trim();
        while (rest.length() > limit) {
            int cut = lastBreak(rest, limit);
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    private static int lastBreak(String s, int limit) {
        int floor = limit / 2;
        for (int i = limit; i > floor; i--) if (s.charAt(i - 1) == '\n') return i;
        for (int i = limit; i > floor; i--) { char c = s.charAt(i - 1); if (c == '.' || c == '!' || c == '?' || c == '。' || c == '다') return i; }
        for (int i = limit; i > floor; i--) if (Character.isWhitespace(s.charAt(i - 1))) return i;
        return limit;
    }

    /** Lines from a page scan back into running text: a line break stays only where a sentence ended. */
    static String reflow(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (out.length() > 0) {
                char last = out.charAt(out.length() - 1);
                boolean ended = last == '.' || last == '!' || last == '?' || last == ':' || last == ';' || last == '。';
                out.append(ended ? '\n' : ' ');
            }
            out.append(line);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ the features

    /** A short Korean summary (3 to 6 bullet points) of the whole text; long texts are summarized piece by piece, then the pieces are summarized together. */
    static String summarize(String text, AtomicBoolean canceled, Progress progress) throws Exception {
        String material = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        List<String> parts = chunks(material, SUMMARY_CHUNK);
        int total = parts.size() + 1, done = 0;
        for (int round = 0; parts.size() > 1 && round < 3; round++) {
            List<String> partial = new ArrayList<>();
            for (String part : parts) {
                partial.add(ask("Summarize the text between <<< and >>> in Korean in 3 to 5 short sentences. Output only the summary.", part, 400, canceled));
                if (progress != null) progress.update(Math.min(++done, total - 1), total);
            }
            parts = chunks(join(partial, "\n"), SUMMARY_CHUNK);
        }
        String result = ask("Summarize the text between <<< and >>> in Korean as 3 to 6 short bullet points, one per line, each starting with \"• \". Output only the bullet points.", parts.isEmpty() ? "" : parts.get(0), 500, canceled);
        if (progress != null) progress.update(total, total);
        return result;
    }

    /** Suggested title and tags. */
    static final class TitleTags {
        String title = "";
        final List<String> tags = new ArrayList<>();
    }

    static TitleTags suggestTitleAndTags(String text, AtomicBoolean canceled) throws Exception {
        String material = text.length() > SUMMARY_CHUNK ? text.substring(0, SUMMARY_CHUNK) : text;
        String reply = ask("Read the text between <<< and >>>. Suggest one short document title (at most 30 characters) and 3 to 5 short topic tags, in the same language as the text. "
            + "Answer in exactly two lines:\nTitle: <title>\nTags: <tag1>, <tag2>, <tag3>", material, 120, canceled);
        TitleTags result = new TitleTags();
        for (String line : reply.split("\\n")) {
            String l = line.trim();
            String lower = l.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("title:") || l.startsWith("제목:")) result.title = l.substring(l.indexOf(':') + 1).trim().replaceAll("^[\"“”']+|[\"“”']+$", "");
            else if (lower.startsWith("tags:") || l.startsWith("태그:"))
                for (String tag : l.substring(l.indexOf(':') + 1).split("[,、，]")) {
                    String t = tag.trim().replaceFirst("^#", "").trim();
                    if (!t.isEmpty() && t.length() <= 24 && !result.tags.contains(t)) result.tags.add(t);
                }
        }
        if (result.title.isEmpty() && result.tags.isEmpty()) throw new IllegalStateException("제목과 태그를 만들지 못했습니다. 다시 시도하세요");
        return result;
    }

    /** Spelling, spacing and grammar fixes only; the wording and meaning stay as they were. */
    static String proofread(String pageText, AtomicBoolean canceled) throws Exception {
        List<String> out = new ArrayList<>();
        for (String part : chunks(pageText, EDIT_CHUNK))
            out.add(ask("Proofread the text between <<< and >>> in its own language. Fix only spelling, spacing, punctuation and grammar mistakes. "
                + "Keep the wording, meaning, line breaks and language. Output only the corrected text, with no comments.", part, 1500, canceled));
        return join(out, "\n");
    }

    /** Translation of one page of text into [languageName]. */
    static String translate(String pageText, String languageName, AtomicBoolean canceled) throws Exception {
        List<String> out = new ArrayList<>();
        for (String part : chunks(pageText, EDIT_CHUNK))
            out.add(ask("Translate the text between <<< and >>> into " + languageName + ". Keep the line breaks. Output only the translation, with no comments.", part, 1500, canceled));
        return join(out, "\n");
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder out = new StringBuilder();
        for (String p : parts) { if (p.isEmpty()) continue; if (out.length() > 0) out.append(separator); out.append(p); }
        return out.toString();
    }
}
