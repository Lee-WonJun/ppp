(ns edit-demo-video
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def scenario-copy
  {"DEMO-01"
   {:prompt "Describe a playable Snake game"
    :prompt-narration "My first request is a playable Snake game with a real browser timer and keyboard controls."
    :caption "Codex uses GPT-5.6 to turn the current product context into a structured change that PPP validates before activation."
    :narration "Codex uses GPT-5.6 to generate a change. PPP validates it before activating the game without a refresh."}
   "DEMO-02"
   {:prompt "Add real product accounts"
    :prompt-narration "Next I ask for real product accounts without replacing the running game."
    :caption "The same conversation crosses the server boundary and adds real product accounts without replacing Snake."
    :narration "The same conversation crosses the server boundary and adds real signup and sign-in while preserving Snake."}
   "DEMO-03"
   {:prompt "Improve the account experience"
    :prompt-narration "The first account screen feels bolted on, so I ask for better design and useful errors."
    :caption "A visible validation error is repaired, then Player One signs up, signs out, signs in, and survives reload."
    :narration "A real validation error explains what to fix. Then Player One signs up, signs out, signs in, and stays signed in after reload."}
   "DEMO-04"
   {:prompt "Add an authenticated Snake ranking"
    :prompt-narration "Now I add a persistent ranking for the signed-in player."
    :caption "The signed-in player saves a score through a server action, and SQLite keeps the ranking after reload."
    :narration "The signed-in player saves a score through a server action. SQLite keeps the ranking after reload."}
   "DEMO-05"
   {:prompt "Turn one game into a platform"
    :prompt-narration "A product decision turns the single game into a platform."
    :caption "A product decision creates a Game library while preserving Snake, the account, and ranking data."
    :narration "The product becomes a game library while preserving Snake, the account, and the ranking data."}
   "DEMO-06"
   {:prompt "Add Tetris without losing anything"
    :prompt-narration "Finally I add Tetris without losing any existing behavior or data."
    :caption "Tetris joins the platform while the existing browser game and server-owned data remain intact."
    :narration "Tetris joins as the second game. Browser interactions and server-owned data keep evolving inside one live product workspace."}})

(def intro-copy
  {:caption "For many product people, the hardest part is not prompting. It is getting past installs, Git, builds, and authentication."
   :narration "For many product people, the hardest part is not prompting. It is getting past installs, Git, builds, and authentication."})

(def final-copy
  {:caption "Where product conversations become running software."
   :narration "Where product conversations become running software."})

(def required-scenarios
  ["DEMO-01" "DEMO-02" "DEMO-03" "DEMO-04" "DEMO-05" "DEMO-06"])

(defn- command-result
  [command options]
  @(process/process command (merge {:out :string :err :string} options)))

(defn- checked-command!
  [command options]
  (let [result (command-result command options)]
    (when-not (zero? (:exit result))
      (binding [*out* *err*]
        (println (:out result))
        (println (:err result)))
      (throw (ex-info "Demo video command failed"
                      {:command (first command)
                       :exit (:exit result)})))
    result))

(defn- command-path
  [name]
  (some-> (command-result ["sh" "-lc" (str "command -v " name)] {})
          :out
          str/trim
          not-empty))

(defn- ffmpeg-path
  []
  (or (some-> (System/getenv "PPP_FFMPEG") str/trim not-empty)
      (command-path "ffmpeg")
      (let [fallback "/mnt/c/Program Files/ImageMagick-7.1.1-Q16-HDRI/ffmpeg.exe"]
        (when (fs/regular-file? fallback) fallback))
      (throw (ex-info "ffmpeg is required for demo capture"
                      {:code :demo-capture/ffmpeg-missing}))))

(defn- windows-executable?
  [path]
  (str/ends-with? (str/lower-case path) ".exe"))

(defn- native-path
  [windows? path]
  (if-not windows?
    (str (fs/absolutize path))
    (-> (checked-command! ["wslpath" "-w" (str (fs/absolutize path))] {})
        :out
        str/trim)))

(defn- concat-path
  [windows? path]
  (-> (native-path windows? path)
      (str/replace "\\" "/")
      (str/replace "'" "'\\''")))

(defn- parse-duration
  [probe-output]
  (when-let [[_ hours minutes seconds]
             (re-find #"Duration: (\d+):(\d+):([0-9.]+)" probe-output)]
    (+ (* 3600 (parse-long hours))
       (* 60 (parse-long minutes))
       (parse-double seconds))))

(defn- probe!
  [ffmpeg windows? path]
  (let [result (command-result [ffmpeg "-i" (native-path windows? path)] {})
        output (str (:out result) "\n" (:err result))
        duration (parse-duration output)]
    (when-not duration
      (throw (ex-info "Could not read demo video duration"
                      {:path (str path)})))
    {:duration duration :output output}))

(defn- seconds
  [milliseconds]
  (/ (double milliseconds) 1000.0))

(defn- clip-segment
  ([scenario start end caption narration]
   (clip-segment scenario start end caption narration 0.0 0.0))
  ([scenario start end caption narration hold]
   (clip-segment scenario start end caption narration hold 0.0))
  ([scenario start end caption narration hold hold-start]
   {:kind :clip
    :scenario scenario
    :source-start (max 0.0 start)
    :source-end (max (+ start 0.5) end)
    :hold hold
    :hold-start hold-start
    :caption caption
    :narration narration}))

(defn- card-segment
  [scenario headline detail duration]
  {:kind :card
   :scenario scenario
   :duration duration
   :headline headline
   :detail detail
   :caption (str headline ". " detail)})

(defn- capture-number
  [record key]
  (let [value (get-in record [:capture key])]
    (when-not (number? value)
      (throw (ex-info "Capture timeline is incomplete"
                      {:scenario (:scenario record) :field key})))
    (double value)))

(defn- validate-records!
  [records raw-duration]
  (let [timelines
        (mapv (fn [record]
                {:scenario (:scenario record)
                 :started (seconds (capture-number record
                                                   :scenario-start-ms))
                 :generated (seconds (capture-number
                                      record :generation-complete-ms))
                 :verified (seconds (capture-number
                                     record :verification-complete-ms))})
              records)]
    (when-not (= required-scenarios (mapv :scenario records))
      (throw (ex-info "Capture scenarios are missing or out of order"
                      {:scenarios (mapv :scenario records)})))
    (doseq [{:keys [scenario started generated verified]} timelines]
      (when-not (and (<= 0.0 started generated verified raw-duration)
                     (true? (:browser-outcome
                             (first (filter #(= scenario (:scenario %))
                                            records)))))
        (throw (ex-info "Capture marker is invalid or outside the raw video"
                        {:scenario scenario
                         :started started
                         :generated generated
                         :verified verified
                         :raw-duration raw-duration}))))
    (doseq [[previous current] (partition 2 1 timelines)]
      (when (> (:verified previous) (:started current))
        (throw (ex-info "Capture scenarios overlap"
                        {:previous (:scenario previous)
                         :current (:scenario current)}))))
    timelines))

(defn- build-segments
  [records raw-duration]
  (let [first-start (seconds (capture-number (first records)
                                             :scenario-start-ms))
        ;; The raw capture opens on Projects, but the create/open transition is
        ;; intentionally fast. Hold that real first frame long enough for a
        ;; judge to read it before continuing into the untouched browser flow.
        intro-start 0.1
        intro-end (min raw-duration (+ first-start 14.0))
        intro [(clip-segment "INTRO" intro-start intro-end
                             (:caption intro-copy)
                             (:narration intro-copy) 0.0 2.0)]]
    (vec
     (concat
      intro
      (mapcat
       (fn [index record]
         (let [scenario (:scenario record)
               {:keys [prompt prompt-narration caption narration]}
               (get scenario-copy scenario)
               started (seconds (capture-number record :scenario-start-ms))
               generated (seconds (capture-number record
                                                  :generation-complete-ms))
               verified (seconds (capture-number record
                                                 :verification-complete-ms))
               prompt-end (min (- generated 0.75) (+ started 14.0))
               outcome-start (max (+ started 0.5) (- generated 1.0))
               outcome-end (min raw-duration (+ verified 1.0))]
           (concat
            (when (pos? index)
              [(clip-segment scenario started prompt-end prompt
                             prompt-narration)])
            [(card-segment scenario "Generation time compressed"
                           "Real Codex output" 4.0)
             (clip-segment scenario outcome-start outcome-end
                           caption narration 6.0)])))
       (range)
       records)
      [(assoc (card-segment "FINAL" "Programmable Programming Page"
                            "Where product conversations become running software"
                            6.0)
              :narration (:narration final-copy))]))))

(defn- segment-duration
  [segment]
  (or (:duration segment)
      (+ (- (:source-end segment) (:source-start segment))
         (double (or (:hold-start segment) 0.0))
         (double (or (:hold segment) 0.0)))))

(defn- timestamp
  [value]
  (let [milliseconds (long (Math/round (* 1000.0 value)))
        hours (quot milliseconds 3600000)
        remainder (mod milliseconds 3600000)
        minutes (quot remainder 60000)
        remainder (mod remainder 60000)
        seconds (quot remainder 1000)
        millis (mod remainder 1000)]
    (format "%02d:%02d:%02d,%03d" hours minutes seconds millis)))

(defn- segment-timeline
  [segments]
  (loop [remaining segments
         start 0.0
         result []]
    (if-let [segment (first remaining)]
      (let [end (+ start (segment-duration segment))]
        (recur (rest remaining)
               end
               (conj result (assoc segment
                                   :final-start start
                                   :final-end end))))
      result)))

(defn- write-subtitles!
  [path timeline]
  (let [entries
        (->> timeline
             (keep-indexed
              (fn [index {:keys [caption final-start final-end]}]
                (when (not (str/blank? caption))
                  (str (inc index) "\n"
                       (timestamp final-start) " --> " (timestamp final-end) "\n"
                       caption "\n")))))]
    (spit (str path) (str/join "\n" entries))))

(defn- validate-language-contract!
  [timeline subtitles]
  (let [spoken (str/join " " (keep :narration timeline))
        written (slurp (str subtitles))]
    (when (or (re-find #"[가-힣]" spoken)
              (re-find #"[가-힣]" written)
              (not-every? #(and (str/includes? written %)
                                (str/includes? spoken %))
                          ["Codex" "GPT-5.6"]))
      (throw (ex-info "Demo narration and subtitles must be English and explain Codex/GPT-5.6 use"
                      {:code :demo-capture/language-contract-invalid})))))

(defn- ffmpeg!
  [ffmpeg command]
  (checked-command! (into [ffmpeg "-hide_banner" "-loglevel" "error" "-y"]
                          command)
                    {}))

(defn- render-clip!
  [ffmpeg windows? raw path
   {:keys [source-start source-end hold hold-start]}]
  (ffmpeg!
   ffmpeg
   ["-ss" (format "%.3f" source-start)
    "-t" (format "%.3f" (- source-end source-start))
    "-i" (native-path windows? raw)
    "-vf" (str "scale=1440:900:force_original_aspect_ratio=decrease,"
               "pad=1440:900:(ow-iw)/2:(oh-ih)/2:color=black,fps=30"
               (when (pos? (double (or hold-start 0.0)))
                 (str ",tpad=start_mode=clone:start_duration="
                      (format "%.3f" hold-start)))
               (when (pos? (double (or hold 0.0)))
                 (str ",tpad=stop_mode=clone:stop_duration="
                      (format "%.3f" hold))))
    "-an" "-c:v" "libx264" "-preset" "veryfast" "-crf" "20"
    "-pix_fmt" "yuv420p" "-movflags" "+faststart"
    (native-path windows? path)]))

(defn- card-filter
  [windows? headline detail]
  (let [font (if windows? "Segoe UI" "DejaVu Sans")
        escape-text #(-> %
                         (str/replace "\\" "\\\\")
                         (str/replace ":" "\\:")
                         (str/replace "'" "\\'"))]
    (str "drawtext=font='" font "':text='" (escape-text headline)
         "':fontcolor=white:fontsize=54:x=(w-text_w)/2:y=(h-text_h)/2-40,"
         "drawtext=font='" font "':text='" (escape-text detail)
         "':fontcolor=0xA7ACB8:fontsize=28:x=(w-text_w)/2:y=(h-text_h)/2+38")))

(defn- render-card!
  [ffmpeg windows? path {:keys [duration headline detail]}]
  (ffmpeg!
   ffmpeg
   ["-f" "lavfi"
    "-i" (str "color=c=0x101114:s=1440x900:r=30:d="
              (format "%.3f" duration))
    "-vf" (card-filter windows? headline detail)
    "-an" "-c:v" "libx264" "-preset" "veryfast" "-crf" "20"
    "-pix_fmt" "yuv420p" "-movflags" "+faststart"
    (native-path windows? path)]))

(defn- render-segments!
  [ffmpeg windows? raw segments-root timeline]
  (mapv
   (fn [index segment]
     (let [path (fs/path segments-root (format "%03d.mp4" index))]
       (if (= :clip (:kind segment))
         (render-clip! ffmpeg windows? raw path segment)
         (render-card! ffmpeg windows? path segment))
       path))
   (range)
   timeline))

(defn- concat-segments!
  [ffmpeg windows? segments-root paths output]
  (let [list-path (fs/path segments-root "concat.txt")]
    (spit (str list-path)
          (str/join "\n"
                    (map #(str "file '" (concat-path windows? %) "'") paths)))
    (ffmpeg!
     ffmpeg
     ["-f" "concat" "-safe" "0"
      "-i" (native-path windows? list-path)
      "-c" "copy" "-movflags" "+faststart"
      (native-path windows? output)])))

(defn- powershell-path
  []
  (let [path "/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"]
    (when (fs/regular-file? path) path)))

(defn- write-tts-script!
  [path]
  (spit
   (str path)
   (str "param([string]$OutputPath, [string]$Text)\n"
        "Add-Type -AssemblyName System.Speech\n"
        "$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer\n"
        "$voice.SelectVoice('Microsoft Zira Desktop')\n"
        "$voice.Rate = 1\n"
        "$voice.Volume = 100\n"
        "$voice.SetOutputToWaveFile($OutputPath)\n"
        "$voice.Speak($Text)\n"
        "$voice.Dispose()\n")))

(defn- render-narration!
  [windows? final-root timeline]
  (when-let [powershell (powershell-path)]
    (let [script (fs/path final-root "tts.ps1")]
      (write-tts-script! script)
      (->> timeline
           (keep-indexed
            (fn [index {:keys [narration final-start]}]
              (when (not (str/blank? narration))
                (let [path (fs/path final-root (format "voice-%02d.wav" index))]
                  (checked-command!
                   [powershell "-NoProfile" "-ExecutionPolicy" "Bypass"
                    "-File" (native-path windows? script)
                    "-OutputPath" (native-path windows? path)
                    "-Text" narration]
                   {})
                  {:path path :delay-ms (long (* 1000.0 (+ final-start 0.25)))}))))
           vec))))

(defn- add-audio!
  [ffmpeg windows? silent-video narrations duration output]
  (if (seq narrations)
    (let [inputs (mapcat (fn [{:keys [path]}]
                           ["-i" (native-path windows? path)])
                         narrations)
          silence-index (inc (count narrations))
          labels (map-indexed
                  (fn [index {:keys [delay-ms]}]
                    (str "[" (inc index) ":a]adelay=" delay-ms "|" delay-ms
                         ",volume=1.0[a" index "]"))
                  narrations)
          mix-inputs (str (apply str (map-indexed (fn [index _]
                                                    (str "[a" index "]"))
                                                  narrations))
                          "[silence]")
          filter (str (str/join ";" labels) ";"
                      "[" silence-index ":a]volume=0[silence];"
                      mix-inputs "amix=inputs=" (inc (count narrations))
                      ":duration=longest:dropout_transition=0[voice]")]
      (ffmpeg!
       ffmpeg
       (vec
        (concat
         ["-i" (native-path windows? silent-video)]
         inputs
         ["-f" "lavfi" "-t" (format "%.3f" duration)
          "-i" "anullsrc=channel_layout=stereo:sample_rate=44100"]
         ["-filter_complex" filter
          "-map" "0:v:0" "-map" "[voice]"
          "-c:v" "copy" "-c:a" "aac" "-b:a" "192k"
          "-t" (format "%.3f" duration) "-movflags" "+faststart"
          (native-path windows? output)]))))
    (fs/copy silent-video output {:replace-existing true})))

(defn- embed-subtitles!
  [ffmpeg windows? voiced-video subtitles output]
  (ffmpeg!
   ffmpeg
   ["-i" (native-path windows? voiced-video)
    "-i" (native-path windows? subtitles)
    "-map" "0:v:0" "-map" "0:a:0?" "-map" "1:0"
    "-c:v" "copy" "-c:a" "copy" "-c:s" "mov_text"
    "-metadata:s:s:0" "language=eng" "-movflags" "+faststart"
    (native-path windows? output)]))

(defn- validate-final!
  [ffmpeg windows? final-path subtitles expected-duration minimum-duration]
  (let [{:keys [duration output]} (probe! ffmpeg windows? final-path)]
    (when-not (and (> duration minimum-duration)
                   (< duration 180.0)
                   (< (Math/abs (- duration expected-duration)) 2.5)
                   (str/includes? output "Video: h264")
                   (str/includes? output "1440x900")
                   (str/includes? output "Audio: aac")
                   (str/includes? output "Subtitle: mov_text")
                   (fs/regular-file? subtitles)
                   (pos? (fs/size subtitles)))
      (throw (ex-info "Final demo video did not satisfy the media contract"
                      {:duration duration
                       :expected expected-duration
                       :h264 (str/includes? output "Video: h264")
                       :size (str/includes? output "1440x900")
                       :aac (str/includes? output "Audio: aac")
                       :subtitles (str/includes? output "Subtitle: mov_text")})))
    {:duration duration
     :width 1440
     :height 900
     :video-codec :h264
     :audio-codec :aac
     :subtitle-codec :mov-text}))

(let [[raw-path observations-path output-root] *command-line-args*]
  (when-not (every? #(and (string? %) (not (str/blank? %)))
                    [raw-path observations-path output-root])
    (throw (ex-info
            "Usage: edit_demo_video.clj RAW_WEBM OBSERVATIONS_JSON OUTPUT_ROOT"
            {:code :demo-capture/arguments-invalid})))
  (let [ffmpeg (ffmpeg-path)
        windows? (windows-executable? ffmpeg)
        observations (json/parse-string (slurp observations-path) true)
        records (:records observations)
        raw (fs/absolutize raw-path)
        final-root (fs/path output-root "final")
        segments-root (fs/path output-root "edit-segments")
        raw-probe (probe! ffmpeg windows? raw)
        segments (build-segments records (:duration raw-probe))
        timeline (segment-timeline segments)
        duration (:final-end (last timeline))
        subtitles (fs/path final-root "ppp-demo.en.srt")
        silent-video (fs/path final-root "ppp-demo-silent.mp4")
        voiced-video (fs/path final-root "ppp-demo-voiced.mp4")
        final-video (fs/path final-root "ppp-demo.mp4")
        minimum-duration (or (some-> (System/getenv
                                      "PPP_DEMO_CAPTURE_MIN_SECONDS")
                                     parse-double)
                             150.0)]
    (validate-records! records (:duration raw-probe))
    (fs/create-dirs final-root)
    (fs/create-dirs segments-root)
    (write-subtitles! subtitles timeline)
    (validate-language-contract! timeline subtitles)
    (let [paths (render-segments! ffmpeg windows? raw segments-root timeline)]
      (concat-segments! ffmpeg windows? segments-root paths silent-video))
    (let [narrations (render-narration! windows? final-root timeline)]
      (add-audio! ffmpeg windows? silent-video narrations duration voiced-video))
    (embed-subtitles! ffmpeg windows? voiced-video subtitles final-video)
    (let [validation (validate-final! ffmpeg windows? final-video subtitles
                                      duration minimum-duration)]
      (spit (str (fs/path final-root "capture.edn"))
            (pr-str {:format-version 1
                     :provider :codex
                     :scenario-count 6
                     :raw-video (str raw)
                     :final-video (str final-video)
                     :subtitles (str subtitles)
                     :segment-count (count timeline)
                     :validation validation
                     :passed? true}))
      (println (pr-str {:demo-capture :passed
                        :video (str final-video)
                        :subtitles (str subtitles)
                        :duration-seconds (:duration validation)
                        :dimensions [1440 900]
                        :provider :codex})))))
