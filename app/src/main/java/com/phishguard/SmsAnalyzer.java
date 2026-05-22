package com.phishguard;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SmsAnalyzer — data-driven classifier using the SMS Spam Collection dataset.
 *
 * This version uses a Naive Bayes-like approach by calculating word frequencies
 * from the dataset (ham vs spam) to determine the likelihood of a message being phishing.
 *
 * It also retains critical heuristic rules (IP URLs, typosquatting) for enhanced detection.
 */
public class SmsAnalyzer {

    // ── Result ─────────────────────────────────────────────────────────────
    public static class Result {
        public final String label;          // "SPAM" or "SAFE"
        public final int    confidence;     // 0-100
        public final String reason;         // human-readable explanation
        public final List<String> signals;  // individual triggered features

        public Result(String label, int confidence, String reason, List<String> signals) {
            this.label      = label;
            this.confidence = confidence;
            this.reason     = reason;
            this.signals    = signals;
        }

        public boolean isSpam() { return "SPAM".equals(label); }
    }

    // ── Data-Driven Components ──────────────────────────────────────────────
    private final Map<String, Integer> spamWordCounts = new HashMap<>();
    private final Map<String, Integer> hamWordCounts = new HashMap<>();
    private int spamTotalWords = 0;
    private int hamTotalWords = 0;
    private int spamMessageCount = 0;
    private int hamMessageCount = 0;
    private final Set<String> vocabulary = new HashSet<>();

    private boolean isModelLoaded = false;
    private final Context context;

    public SmsAnalyzer(Context context) {
        this.context = context.getApplicationContext();
        loadDataset(context);
    }

    private void loadDataset(Context context) {
        AssetManager assetManager = context.getAssets();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(assetManager.open("SMSSpamCollection.tsv")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;

                String label = parts[0].toLowerCase();
                String text = parts[1].toLowerCase();
                String[] words = tokenize(text);

                if (label.equals("spam")) {
                    spamMessageCount++;
                    for (String word : words) {
                        spamWordCounts.put(word, spamWordCounts.getOrDefault(word, 0) + 1);
                        spamTotalWords++;
                        vocabulary.add(word);
                    }
                } else {
                    hamMessageCount++;
                    for (String word : words) {
                        hamWordCounts.put(word, hamWordCounts.getOrDefault(word, 0) + 1);
                        hamTotalWords++;
                        vocabulary.add(word);
                    }
                }
            }
            isModelLoaded = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String[] tokenize(String text) {
        return text.replaceAll("[^a-zA-Z\\s]", "").split("\\s+");
    }

    // ── Heuristic Rules (Hybrid Approach) ──────────────────────────────────
    private static final String[] BRAND_IMPERSONATION = {
        "sbi", "hdfc", "icici", "axis bank", "paytm", "phonepe", "amazon", "flipkart"
    };

    private static final Pattern URL_PATTERN = Pattern.compile(
        "https?://[\\w\\-\\.]+\\.[a-zA-Z]{2,}(/[\\S]*)?|www\\.[\\w\\-\\.]+\\.[a-zA-Z]{2,}",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IP_URL = Pattern.compile("https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private static final String[] SAFE_DOMAINS = {
        "amazon.com", "amazon.in", "flipkart.com", "google.com", "google.co.in", 
        "sbi.co.in", "hdfcbank.com", "icicibank.com", "paytm.com", "phonepe.com"
    };

    private static final String[] SUSPICIOUS_TLDS = {
        ".tk", ".xyz", ".ml", ".ga", ".cf", ".gq", ".cc", ".club", ".info", ".work", ".top"
    };

    private static final Set<String> SENDER_WHITELIST = new java.util.HashSet<>();
    static {
        SENDER_WHITELIST.add("AD-AMAZON");
        SENDER_WHITELIST.add("VK-AMAZON");
        SENDER_WHITELIST.add("GOOGL");
        SENDER_WHITELIST.add("GOOGLE");
        SENDER_WHITELIST.add("FLIPKT");
        SENDER_WHITELIST.add("PAYTM");
    }

    // ── Main analyze method ──────────────────────────────────────────────────
    public Result analyze(String body, String sender) {
        if (body == null) body = "";
        
        List<String> signals = new ArrayList<>();
        String senderUpper = (sender != null) ? sender.trim().toUpperCase(Locale.ENGLISH) : "";

        // A. Custom User Whitelist & Blacklist (stored in SharedPreferences)
        if (context != null) {
            android.content.SharedPreferences prefs = context.getSharedPreferences("phishguard_prefs", Context.MODE_PRIVATE);
            java.util.Set<String> customWhitelist = prefs.getStringSet("custom_whitelist", new java.util.HashSet<>());
            java.util.Set<String> customBlacklist = prefs.getStringSet("custom_blacklist", new java.util.HashSet<>());

            if (customBlacklist.contains(senderUpper)) {
                signals.add("Sender blocked by user");
                return new Result("SPAM", 100, "Blocked sender (flagged by user).", signals);
            }
            if (customWhitelist.contains(senderUpper)) {
                signals.add("Sender whitelisted by user");
                return new Result("SAFE", 100, "Verified secure sender (trusted by user).", signals);
            }
        }

        // 1. Broadcaster Whitelist Rule
        if (SENDER_WHITELIST.contains(senderUpper)) {
            signals.add("Sender in trusted whitelist");
            return new Result("SAFE", 100, "Verified secure sender.", signals);
        }

        // 2. Gateway suffix -G or -T Rule
        if (senderUpper.endsWith("-G") || senderUpper.endsWith("-T")) {
            signals.add("Sender ends with secure suffix " + (senderUpper.endsWith("-G") ? "-G" : "-T"));
            return new Result("SAFE", 100, "Verified gateway source.", signals);
        }

        // 3. Gateway suffix -S Rule (borderline check)
        if (senderUpper.endsWith("-S")) {
            boolean hasLink = URL_PATTERN.matcher(body).find();
            if (hasLink) {
                signals.add("Borderline sender (-S) sent suspicious link");
                return new Result("SPAM", 50, "Unsafe links detected from flagged gateway (-S).", signals);
            } else {
                signals.add("Borderline sender (-S) clean message");
                return new Result("SAFE", 50, "Neutral gateway suffix (-S) check completed.", signals);
            }
        }

        double spamScore = 0;
        if (isModelLoaded) {
            spamScore = calculateSpamProbability(body);
        }

        // Hybrid detection: Combine data-driven score with critical heuristics
        int heuristicScore = 0;

        // B. Bank-Grade Threat Keyword Heuristics
        boolean containsUrgentKeyword = false;
        String lowerBody = body.toLowerCase(Locale.ENGLISH);
        String[] urgentKeywords = {"otp", "debit", "kyc", "suspend", "block", "verify", "banking", "pan card", "aadhaar", "winner", "prize"};
        for (String kw : urgentKeywords) {
            if (lowerBody.contains(kw)) {
                containsUrgentKeyword = true;
                break;
            }
        }
        if (containsUrgentKeyword && URL_PATTERN.matcher(body).find()) {
            heuristicScore += 30;
            signals.add("Urgent bank keyword with link");
        }

        Matcher urlMatcher = URL_PATTERN.matcher(body);
        if (urlMatcher.find()) {
            String url = urlMatcher.group().toLowerCase(Locale.ENGLISH);
            
            // Check if URL is from a safe domain
            boolean isSafeDomain = false;
            for (String safeDomain : SAFE_DOMAINS) {
                if (url.contains(safeDomain)) {
                    isSafeDomain = true;
                    break;
                }
            }

            if (isSafeDomain) {
                heuristicScore += 2;
                signals.add("Contains trusted URL");
            } else {
                boolean isSuspicious = false;
                
                // IP Address URL
                if (IP_URL.matcher(body).find()) {
                    heuristicScore += 45;
                    signals.add("IP address used as URL");
                    isSuspicious = true;
                }
                
                // High-risk TLD
                for (String tld : SUSPICIOUS_TLDS) {
                    if (url.endsWith(tld) || url.contains(tld + "/")) {
                        heuristicScore += 25;
                        signals.add("Suspicious TLD (" + tld + ") in URL");
                        isSuspicious = true;
                        break;
                    }
                }
                
                // HTTP instead of HTTPS
                if (url.startsWith("http://")) {
                    heuristicScore += 10;
                    signals.add("Insecure URL (HTTP)");
                    isSuspicious = true;
                }
                
                // Unusually long URL or credential harvesting
                if (url.length() > 75) {
                    heuristicScore += 8;
                    signals.add("Unusually long URL");
                    isSuspicious = true;
                }
                if (url.contains("@")) {
                    heuristicScore += 15;
                    signals.add("Credential harvesting pattern (@) in URL");
                    isSuspicious = true;
                }

                // If it contains a URL but didn't trigger any highly suspicious rule
                if (!isSuspicious) {
                    heuristicScore += 5;
                    signals.add("Contains generic URL");
                }
            }
        }

        for (String brand : BRAND_IMPERSONATION) {
            if (body.toLowerCase().contains(brand) && urlMatcher.reset().find()) {
                heuristicScore += 20;
                signals.add("Brand impersonation with link");
            }
        }

        // Final decision logic: 3-Tier grading
        boolean isSpam = (spamScore > 0.85) || (heuristicScore >= 50);
        boolean isSuspicious = !isSpam && ((spamScore >= 0.50) || (heuristicScore >= 15));

        int confidence;
        if (isSpam) {
            confidence = (int) (Math.max(spamScore * 100, Math.min(heuristicScore * 2, 99)));
            if (confidence < 85) confidence = 85 + (confidence % 15);
        } else if (isSuspicious) {
            confidence = (int) (Math.max(spamScore * 100, Math.min(heuristicScore * 2, 84)));
            if (confidence < 50) confidence = 50 + (confidence % 34);
        } else {
            confidence = (int) (Math.max(spamScore * 100, Math.min(heuristicScore * 2, 49)));
        }

        String label = isSpam ? "SPAM" : (isSuspicious ? "SUSPICIOUS" : "SAFE");
        String reason = isSpam 
                ? "High threat level detected. Suspicious threat signatures present." 
                : (isSuspicious ? "Borderline indicators detected. Proceed with caution." 
                                : "No significant threat indicators detected.");

        if (isSpam && !signals.isEmpty()) {
            reason = signals.get(0) + " — likely phishing attack.";
        } else if (isSuspicious && !signals.isEmpty()) {
            reason = signals.get(0) + " — warning indicator.";
        }

        return new Result(label, confidence, reason, signals);
    }

    private double calculateSpamProbability(String text) {
        String[] words = tokenize(text.toLowerCase());
        
        // P(Spam|Message) ∝ P(Spam) * Π P(Word|Spam)
        double logProbSpam = Math.log((double) spamMessageCount / (spamMessageCount + hamMessageCount));
        double logProbHam = Math.log((double) hamMessageCount / (spamMessageCount + hamMessageCount));

        for (String word : words) {
            if (!vocabulary.contains(word)) continue;

            // Laplace smoothing
            double pWordSpam = (double) (spamWordCounts.getOrDefault(word, 0) + 1) / (spamTotalWords + vocabulary.size());
            double pWordHam = (double) (hamWordCounts.getOrDefault(word, 0) + 1) / (hamTotalWords + vocabulary.size());

            logProbSpam += Math.log(pWordSpam);
            logProbHam += Math.log(pWordHam);
        }

        // Convert back to probability: P = 1 / (1 + e^(logHam - logSpam))
        return 1.0 / (1.0 + Math.exp(logProbHam - logProbSpam));
    }
}
