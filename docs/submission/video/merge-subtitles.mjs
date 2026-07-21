import fs from "node:fs";
import path from "node:path";

const root = path.resolve(process.argv[2]);
const durationsPath = path.join(root, "durations.tsv");
const audioRoot = path.join(root, "audio");
const outputPath = path.join(root, "ppp-build-week-demo.en.srt");

const toMs = (value) => {
  const match = value.match(/(\d+):(\d+):(\d+)[,.](\d+)/);
  if (!match) throw new Error(`Invalid SRT timestamp: ${value}`);
  return Number(match[1]) * 3600000 + Number(match[2]) * 60000 +
    Number(match[3]) * 1000 + Number(match[4].padEnd(3, "0").slice(0, 3));
};

const format = (milliseconds) => {
  const ms = Math.max(0, Math.round(milliseconds));
  const hours = Math.floor(ms / 3600000);
  const minutes = Math.floor((ms % 3600000) / 60000);
  const seconds = Math.floor((ms % 60000) / 1000);
  const millis = ms % 1000;
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")},${String(millis).padStart(3, "0")}`;
};

const durations = fs.readFileSync(durationsPath, "utf8").trim().split("\n")
  .filter(Boolean)
  .map((line) => {
    const [scene, duration] = line.split("\t");
    return { scene, duration: Number(duration) };
  });

let offsetMs = 0;
let cueNumber = 1;
const merged = [];

for (const { scene, duration } of durations) {
  const source = fs.readFileSync(path.join(audioRoot, `${scene}.srt`), "utf8").trim();
  const blocks = source.split(/\r?\n\r?\n/).filter(Boolean);
  for (const block of blocks) {
    const lines = block.split(/\r?\n/);
    const timingIndex = lines.findIndex((line) => line.includes(" --> "));
    if (timingIndex < 0) continue;
    const [start, end] = lines[timingIndex].split(" --> ");
    const text = lines.slice(timingIndex + 1).join("\n").trim();
    if (!text) continue;
    merged.push(String(cueNumber++));
    merged.push(`${format(offsetMs + toMs(start))} --> ${format(offsetMs + toMs(end))}`);
    merged.push(text);
    merged.push("");
  }
  offsetMs += duration * 1000;
}

fs.writeFileSync(outputPath, `${merged.join("\n").trim()}\n`);
