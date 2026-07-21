import { test, expect } from "@playwright/test";
import { readFileSync, writeFileSync } from "node:fs";

const observationPath = process.env.PPP_DEMO_LIVE_OBSERVATIONS;
const accessCode = process.env.PPP_DEMO_LIVE_ACCESS_CODE || "ppp-demo-live";
const publicPassword = process.env.PPP_DEMO_PUBLIC_PASSWORD;
const publicCapture = process.env.PPP_DEMO_PUBLIC_CAPTURE === "1";
const resumeExisting = process.env.PPP_DEMO_LIVE_RESUME_FINAL === "1";

if (publicCapture) {
  const target = new URL(
    process.env.PPP_LIVE_BASE_URL || "https://ppp.openai.slopbook.org/"
  );
  if (target.origin !== "https://ppp.openai.slopbook.org") {
    throw new Error("Public capture must target the public judge origin");
  }
  if (!publicPassword) {
    throw new Error("PPP_DEMO_PUBLIC_PASSWORD is required for public capture");
  }
}

if (!observationPath) {
  throw new Error("PPP_DEMO_LIVE_OBSERVATIONS is required");
}

const prompts = {
  "DEMO-01": `Build the first game in this product: a polished Snake game. It must start moving automatically from a browser timer and continue or restart automatically after a collision. Give the playable root the accessible name "Snake game", make that root keyboard-focusable, and handle real DOM ArrowUp, ArrowDown, ArrowLeft, and ArrowRight keydown events without a reload. Each accepted key must immediately update the direction state and visible direction output. Show the score and expose live values named "Snake head row", "Snake head column", "Snake direction", and "Snake score" so the running game can be verified. Keep all game logic in the browser sandbox. Do not write server, shared-domain, test, or migration files yet.`,
  "DEMO-02": `Add real product signup and sign-in to this Snake product, while keeping Snake playable. Use the provided product-auth capabilities, not PPP workspace access, and never store passwords yourself. Start with a simple account area with fields named "Display name", "Sign-in ID", and "Password", a button named "Create account", and a switch named "Have an account? Sign in". The sign-in submit button must be named "Sign in" and the reverse switch must be named "Need an account? Create one". Store the public display-name profile in a host-approved migration keyed by the auth user id. After signup or sign-in show "Signed in as <display name>" and a button named "Sign out". Add rollback-only domain tests for signup, profile lookup, sign-in state, and rejection paths.`,
  "DEMO-03": `The account area feels bolted onto the game. Redesign it as a cohesive arcade account panel and make invalid input, duplicate-account, wrong-password, and server failures visibly explain what the player can fix. Keep the existing Snake game and every product-auth/server action unchanged. Put the latest visible account result in an output named "Account message". This is a client-only presentation and error-handling change: do not write server, shared-domain, test, or migration files.`,
  "DEMO-04": `Add a persistent Snake ranking for signed-in players. Keep the game and account experience. Add a button named "Save score" that sends the current Snake score to a registered server action. The action must require the authenticated product user, derive identity from the auth context instead of trusting a typed player name, store the player's best score in SQLite, and return a deterministic ranking ordered by score descending with stable ties. Render it in a region named "Snake ranking" and show the signed-in player's display name and score. Put action feedback in an output named "Ranking status". Add a host-approved migration and rollback-only domain tests for auth enforcement, best-score updates, ordering, response shape, and reload persistence.`,
  "DEMO-05": `This should be a game platform, not only a Snake page. Turn the product into an arcade with a home view headed "Game library" and a catalog card for Snake with a button named "Play Snake". Snake must remain playable as one game and keep the signed-in account, existing SQLite ranking, stored score, and full account controls. Add a button named "Back to games" on the Snake view. This is a client-only information-architecture change: reuse every existing server action and do not write server, shared-domain, test, or migration files.`,
  "DEMO-06": `Add Tetris as the second game in the existing Game library without removing or replacing Snake. Its catalog button must be named "Play Tetris". Tetris must advance automatically from a browser timer, accept ArrowLeft, ArrowRight, and ArrowDown without reload, use a playable root named "Tetris game", and expose live values named "Piece row" and "Piece column". Include a "Back to games" button. Preserve the signed-in account, Snake ranking, stored score, playable Snake, and every existing server action. This is client-only: do not write server, shared-domain, test, or migration files.`
};

const publicPrompts = {
  "DEMO-01": prompts["DEMO-01"],
  "PUBLIC-02": `Add one real server-powered feature to the existing Snake game without replacing it. Register a server action that accepts the current Snake score and returns that score plus 100. Add a button named "Boost score", an output named "Boosted score", and an output named "Server response". Clicking the button must call the server action and show the returned value. Keep Snake's timer and keyboard controls. Do not add SQL or migrations. Add only the minimal rollback-only domain tests for the server rule and response shape.`,
  "PUBLIC-03": `Change the existing server score rule without redesigning the page or replacing Snake. The same registered action must now multiply the submitted score by 3 instead of adding 100. Keep the same "Boost score" button, "Boosted score" output, and "Server response" output. Add an output named "Score rule" containing "Triple server rule". Update only the existing rollback-only domain tests for the new rule. Do not add SQL or migrations.`,
  "PUBLIC-04": `Turn this product into a small arcade while preserving the playable Snake game and its working server-powered score feature. Add a home view headed "Game library" with a Snake catalog card and a button named "Play Snake". Add a button named "Back to games" on the Snake view. This is a client-only information-architecture change: reuse the existing server action and do not write server, shared-domain, test, or migration files.`,
  "PUBLIC-05": `Add Tetris as the second game in the existing Game library without removing or replacing Snake. Its catalog button must be named "Play Tetris". Tetris must advance automatically from a browser timer, accept ArrowLeft, ArrowRight, and ArrowDown without reload, use a playable root named "Tetris game", and expose live values named "Piece row" and "Piece column". Include a "Back to games" button. Preserve playable Snake, the working triple-score server action, and every existing server action. This is client-only: do not write server, shared-domain, test, or migration files.`
};

const scenarioOrder = [
  "DEMO-01",
  "DEMO-02",
  "DEMO-03",
  "DEMO-04",
  "DEMO-05",
  "DEMO-06"
];
const publicScenarioOrder = [
  "DEMO-01",
  "PUBLIC-02",
  "PUBLIC-03",
  "PUBLIC-04",
  "PUBLIC-05"
];
const activePrompts = publicCapture ? publicPrompts : prompts;
const activeScenarioOrder = publicCapture ? publicScenarioOrder : scenarioOrder;
const maximumSemanticRepairs = Number(
  process.env.PPP_DEMO_SEMANTIC_REPAIRS || "3"
);

function productFrame(page) {
  return page.frameLocator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  );
}

async function snapshot(page) {
  if (publicCapture) {
    return page.evaluate(async () => {
      const sessionId = new URL(location.href).searchParams.get("session");
      if (!sessionId) return null;
      const response = await fetch(
        `/api/sessions/${encodeURIComponent(sessionId)}`,
        { credentials: "same-origin" }
      );
      if (!response.ok) return null;
      const session = await response.json();
      return {
        "session-id": session.id,
        version: session["current-version"],
        connection: "connected",
        progress: null,
        messages: [],
        "debug-error": null
      };
    });
  }
  return page.evaluate(() => window.__PPP_TEST__?.snapshot());
}

async function waitForRuntime(page, sessionId, version) {
  await expect.poll(async () => {
    const current = await snapshot(page);
    const activeFrames = publicCapture ? await activeFrameCount(page) : 1;
    return current && current["session-id"] === sessionId
      && current.version === version
      && current.connection === "connected"
      && activeFrames === 1;
  }, { timeout: 45_000 }).toBe(true);
}

async function createFreshSession(page) {
  const entryPath = publicCapture
    ? "/?test-runtime=1"
    : `/?test-runtime=1#access=${encodeURIComponent(accessCode)}`;
  await page.goto(entryPath, {
    waitUntil: "domcontentloaded",
    timeout: 30_000
  });
  if (publicCapture) {
    const signIn = page.getByRole("region", { name: "Workspace sign in" });
    await expect(signIn).toBeVisible();
    await signIn.getByLabel("Password").fill(publicPassword);
    await signIn.getByRole("button", { name: "Continue" }).click();
  }
  const projects = page.getByRole("region", { name: "Projects" });
  await expect(projects).toBeVisible();
  await projects.getByRole("button", { name: "New project" }).first().click();
  const form = page.getByRole("form", { name: "Create project" });
  await form.getByLabel("Project name").fill("Arcade evolution");
  await form.getByRole("button", { name: "Create project" }).click();

  await expect(page.getByRole("button", {
    name: "Open product conversation"
  })).toBeVisible({ timeout: 30_000 });
  await expect.poll(() => new URL(page.url()).searchParams.get("session"), {
    timeout: 30_000
  }).toMatch(/^[0-9a-f-]{36}$/);
  const sessionId = new URL(page.url()).searchParams.get("session");
  await waitForRuntime(page, sessionId, 0);
  if (publicCapture) {
    await page.locator(".ppp-handle").click();
  } else {
    await page.evaluate(() => window.__PPP_TEST__.openSidebar());
  }
  await expect(productFrame(page).getByRole("complementary", {
    name: "Product conversation"
  })).toBeVisible();
  return sessionId;
}

async function openConversation(page) {
  if (publicCapture) {
    const conversation = productFrame(page).getByRole("complementary", {
      name: "Product conversation"
    });
    if (!(await conversation.isVisible().catch(() => false))) {
      await page.locator(".ppp-handle").click();
    }
  } else {
    await page.evaluate(() => window.__PPP_TEST__.openSidebar());
  }
  await expect(productFrame(page).getByRole("complementary", {
    name: "Product conversation"
  })).toBeVisible();
}

async function closeConversation(page) {
  if (publicCapture) {
    const conversation = productFrame(page).getByRole("complementary", {
      name: "Product conversation"
    });
    if (await conversation.isVisible().catch(() => false)) {
      await page.locator(".ppp-handle").click();
    }
    return;
  }
  const close = page.getByRole("button", { name: "Close product conversation" });
  if (await close.count()) {
    await close.click();
  }
}

async function submitTurn(page, prompt) {
  await openConversation(page);
  const before = await snapshot(page);
  const conversation = productFrame(page).getByRole("complementary", {
    name: "Product conversation"
  });
  const articleCount = publicCapture
    ? await conversation.getByRole("article").count()
    : 0;
  const started = Date.now();
  const composer = productFrame(page).getByRole("textbox", { name: "Message" });
  await composer.fill(prompt);
  await composer.press("Enter");
  let terminalFailure = null;
  await expect.poll(async () => {
    const current = await snapshot(page);
    if (!publicCapture) {
      return current.progress === null
        && current.messages.length >= before.messages.length + 2;
    }
    if (current?.version > before.version) return true;
    const articles = conversation.getByRole("article");
    if (await articles.count() >= articleCount + 2) {
      const last = await articles.last().innerText();
      if (/not applied|unchanged|could not be safely applied/i.test(last)) {
        terminalFailure = last.replace(/\s+/g, " ").slice(0, 1400);
        return true;
      }
    }
    return false;
  }, { timeout: 720_000 }).toBe(true);
  const after = await snapshot(page);
  if (after.version > before.version) {
    await waitForRuntime(page, after["session-id"], after.version);
  }
  return {
    before,
    after: terminalFailure
      ? { ...after, "terminal-failure": terminalFailure }
      : after,
    duration: Date.now() - started,
    "completed-at-ms": Date.now()
  };
}

async function activeFrameCount(page) {
  return page.locator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  ).count();
}

function boundedFailureFeedback(error) {
  return String(error?.message || error || "Unknown browser outcome failure")
    .replace(/\s+/g, " ")
    .slice(0, 1400);
}

function writeObservations(sessionId, records) {
  writeFileSync(observationPath, JSON.stringify({
    "format-version": 1,
    "session-id": sessionId,
    records
  }, null, 2));
}

function readObservations() {
  return JSON.parse(readFileSync(observationPath, "utf8"));
}

function numericText(locator) {
  return locator.textContent().then(value => Number(String(value).match(/-?\d+/)?.[0]));
}

async function verifySnake(page) {
  await closeConversation(page);
  const game = productFrame(page).getByLabel("Snake game", { exact: true });
  const row = productFrame(page).getByLabel("Snake head row", { exact: true });
  const column = productFrame(page).getByLabel("Snake head column", { exact: true });
  const direction = productFrame(page).getByLabel("Snake direction", { exact: true });
  const score = productFrame(page).getByLabel("Snake score", { exact: true });
  await expect(game).toBeVisible();
  await expect(row).toBeVisible();
  await expect(column).toBeVisible();
  await expect(direction).toBeVisible();
  await expect(score).toBeVisible();

  const beforePosition = `${await numericText(row)}:${await numericText(column)}`;
  await expect.poll(async () => `${await numericText(row)}:${await numericText(column)}`, {
    timeout: 8_000
  }).not.toBe(beforePosition);

  const currentDirection = String(await direction.textContent()).toLowerCase();
  const vertical = currentDirection.includes("up")
    || currentDirection.includes("down");
  const expectedDirection = vertical ? "left" : "down";
  await game.focus();
  await game.press(vertical ? "ArrowLeft" : "ArrowDown");
  await expect.poll(async () => String(await direction.textContent()).toLowerCase(), {
    timeout: 3_000
  }).toContain(expectedDirection);
  return {
    "snake-timer-advanced": true,
    "snake-keyboard-moved": true,
    "snake-score-visible": true
  };
}

async function verifyAccountShell(page) {
  await closeConversation(page);
  await expect(productFrame(page).getByLabel("Snake game", { exact: true })).toBeVisible();
  await expect(productFrame(page).getByRole("textbox", {
    name: "Display name",
    exact: true
  })).toBeVisible();
  await expect(productFrame(page).getByRole("textbox", {
    name: "Sign-in ID",
    exact: true
  })).toBeVisible();
  await expect(productFrame(page).getByLabel("Password", { exact: true })).toBeVisible();
  await expect(productFrame(page).getByRole("button", {
    name: "Create account",
    exact: true
  })).toBeVisible();
  return {
    "product-auth-form-visible": true,
    "snake-preserved": true
  };
}

async function returnToSignup(page) {
  const signedIn = productFrame(page).getByText(/Signed in as /i).first();
  if (await signedIn.isVisible().catch(() => false)) {
    await productFrame(page).getByRole("button", {
      name: "Sign out",
      exact: true
    }).click();
  }
  const createButton = productFrame(page).getByRole("button", {
    name: "Create account",
    exact: true
  });
  if (!(await createButton.isVisible().catch(() => false))) {
    const createSwitch = productFrame(page).getByRole("button", {
      name: "Need an account? Create one",
      exact: true
    });
    if (await createSwitch.isVisible().catch(() => false)) {
      await createSwitch.click();
    }
  }
  await expect(createButton).toBeVisible();
}

async function verifyAccountExperience(page, sessionId, version) {
  await closeConversation(page);
  await returnToSignup(page);
  const displayName = productFrame(page).getByRole("textbox", {
    name: "Display name",
    exact: true
  });
  const identifier = productFrame(page).getByRole("textbox", {
    name: "Sign-in ID",
    exact: true
  });
  const password = productFrame(page).getByLabel("Password", { exact: true });
  const accountMessage = productFrame(page).getByLabel("Account message", {
    exact: true
  });

  await displayName.fill("Player One");
  await identifier.fill("x");
  await password.fill("arcade password");
  await productFrame(page).getByRole("button", {
    name: "Create account",
    exact: true
  }).click();
  await expect(accountMessage).toContainText(/valid sign-in identifier/i, {
    timeout: 20_000
  });

  const accountId = `player-${Date.now()}`;
  await identifier.fill(accountId);
  await password.fill("arcade password");
  await productFrame(page).getByRole("button", {
    name: "Create account",
    exact: true
  }).click();
  await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
    .toBeVisible({ timeout: 20_000 });

  await productFrame(page).getByRole("button", {
    name: "Sign out",
    exact: true
  }).click();
  await productFrame(page).getByRole("button", {
    name: "Have an account? Sign in",
    exact: true
  }).click();
  await productFrame(page).getByRole("textbox", {
    name: "Sign-in ID",
    exact: true
  }).fill(accountId);
  await productFrame(page).getByLabel("Password", { exact: true })
    .fill("arcade password");
  await productFrame(page).getByRole("button", {
    name: "Sign in",
    exact: true
  }).click();
  await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
    .toBeVisible({ timeout: 20_000 });

  await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
  await waitForRuntime(page, sessionId, version);
  await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
    .toBeVisible({ timeout: 20_000 });
  await expect(productFrame(page).getByLabel("Snake game", { exact: true }))
    .toBeVisible();
  return {
    "invalid-signup-error-visible": true,
    "signup-completed": true,
    "signout-signin-completed": true,
    "authenticated-reload": true,
    "snake-preserved": true
  };
}

async function verifyRanking(page, sessionId, version) {
  await closeConversation(page);
  await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
    .toBeVisible();
  await productFrame(page).getByRole("button", {
    name: "Save score",
    exact: true
  }).click();
  const ranking = productFrame(page).getByRole("region", {
    name: "Snake ranking",
    exact: true
  });
  await expect(ranking).toContainText("Player One", { timeout: 20_000 });
  await expect(productFrame(page).getByLabel("Ranking status", { exact: true }))
    .not.toBeEmpty({ timeout: 20_000 });

  await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
  await waitForRuntime(page, sessionId, version);
  await expect(productFrame(page).getByRole("region", {
    name: "Snake ranking",
    exact: true
  })).toContainText("Player One", { timeout: 20_000 });
  await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
    .toBeVisible();
  return {
    "authenticated-score-saved": true,
    "sqlite-ranking-reloaded": true,
    "account-preserved": true
  };
}

async function verifyPublicRanking(page, sessionId, version) {
  await closeConversation(page);
  await expect(productFrame(page).getByLabel("Snake game", { exact: true }))
    .toBeVisible();
  await productFrame(page).getByRole("button", {
    name: "Boost score",
    exact: true
  }).click();
  const boosted = productFrame(page).getByLabel("Boosted score", {
    exact: true
  });
  await expect(boosted).toContainText(/1\d\d|[2-9]\d\d|\d{4,}/, {
    timeout: 20_000
  });
  await expect(productFrame(page).getByLabel("Server response", { exact: true }))
    .not.toBeEmpty({ timeout: 20_000 });

  await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
  await waitForRuntime(page, sessionId, version);
  await expect(productFrame(page).getByRole("button", {
    name: "Boost score",
    exact: true
  })).toBeVisible({ timeout: 20_000 });
  return {
    "server-action-returned-boosted-score": true,
    "server-feature-survived-reload": true,
    "snake-preserved": true
  };
}

async function verifyPublicRankingRule(page, sessionId, version) {
  await closeConversation(page);
  await expect(productFrame(page).getByLabel("Score rule", { exact: true }))
    .toContainText(/triple server rule/i);
  await productFrame(page).getByRole("button", {
    name: "Boost score",
    exact: true
  }).click();
  const boosted = productFrame(page).getByLabel("Boosted score", {
    exact: true
  });
  await expect.poll(async () => {
    const value = await numericText(boosted);
    return Number.isInteger(value) && value > 0 && value % 3 === 0;
  }, { timeout: 20_000 }).toBe(true);
  await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
  await waitForRuntime(page, sessionId, version);
  await expect(productFrame(page).getByLabel("Score rule", { exact: true }))
    .toContainText(/triple server rule/i);
  return {
    "server-score-rule-changed": true,
    "triple-result-returned": true,
    "snake-preserved": true
  };
}

async function verifyPublicGameLibrary(page) {
  await closeConversation(page);
  await expect(productFrame(page).getByRole("heading", {
    name: "Game library",
    exact: true
  })).toBeVisible();
  await productFrame(page).getByRole("button", {
    name: "Play Snake",
    exact: true
  }).click();
  await expect(productFrame(page).getByLabel("Snake game", { exact: true }))
    .toBeVisible();
  await expect(productFrame(page).getByRole("button", {
    name: "Boost score",
    exact: true
  })).toBeVisible();
  await expect(productFrame(page).getByLabel("Score rule", { exact: true }))
    .toContainText(/triple server rule/i);
  return {
    "game-library-visible": true,
    "snake-listed-and-playable": true,
    "server-feature-preserved": true
  };
}

async function verifyPublicTetrisAddition(page) {
  await closeConversation(page);
  const backToGames = productFrame(page).getByRole("button", {
    name: "Back to games",
    exact: true
  });
  if (await backToGames.isVisible().catch(() => false)) await backToGames.click();
  await productFrame(page).getByRole("button", {
    name: "Play Tetris",
    exact: true
  }).click();
  const game = productFrame(page).getByLabel("Tetris game", { exact: true });
  const row = productFrame(page).getByLabel("Piece row", { exact: true });
  const column = productFrame(page).getByLabel("Piece column", { exact: true });
  await expect(game).toBeVisible();
  const rowBefore = await numericText(row);
  await expect.poll(async () => numericText(row), { timeout: 8_000 })
    .not.toBe(rowBefore);
  const columnBefore = await numericText(column);
  await game.focus();
  await game.press("ArrowLeft");
  await expect.poll(async () => numericText(column), { timeout: 3_000 })
    .not.toBe(columnBefore);
  await productFrame(page).getByRole("button", {
    name: "Back to games",
    exact: true
  }).click();
  await productFrame(page).getByRole("button", {
    name: "Play Snake",
    exact: true
  }).click();
  await expect(productFrame(page).getByRole("button", {
    name: "Boost score",
    exact: true
  })).toBeVisible();
  await expect(productFrame(page).getByLabel("Score rule", { exact: true }))
    .toContainText(/triple server rule/i);
  return {
    "tetris-listed-and-playable": true,
    "tetris-timer-advanced": true,
    "tetris-keyboard-moved": true,
    "snake-server-feature-preserved": true
  };
}

async function verifyGameLibrary(page) {
  await closeConversation(page);
  await expect(productFrame(page).getByRole("heading", {
    name: "Game library",
    exact: true
  })).toBeVisible();
  await expect(productFrame(page).getByRole("button", {
    name: "Play Snake",
    exact: true
  })).toBeVisible();
  await productFrame(page).getByRole("button", {
    name: "Play Snake",
    exact: true
  }).click();
  await expect(productFrame(page).getByLabel("Snake game", { exact: true }))
    .toBeVisible();
  await expect(productFrame(page).getByRole("region", {
    name: "Snake ranking",
    exact: true
  })).toContainText("Player One");
  await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
    .toBeVisible();
  return {
    "game-library-visible": true,
    "snake-listed-and-playable": true,
    "ranking-preserved": true,
    "account-preserved": true
  };
}

async function verifyTetrisAddition(page) {
  await closeConversation(page);
  const backToGames = productFrame(page).getByRole("button", {
    name: "Back to games",
    exact: true
  });
  if (await backToGames.isVisible().catch(() => false)) {
    await backToGames.click();
  }
  await expect(productFrame(page).getByRole("heading", {
    name: "Game library",
    exact: true
  })).toBeVisible();
  await expect(productFrame(page).getByRole("button", {
    name: "Play Snake",
    exact: true
  })).toBeVisible();
  await productFrame(page).getByRole("button", {
    name: "Play Tetris",
    exact: true
  }).click();

  const game = productFrame(page).getByLabel("Tetris game", { exact: true });
  const row = productFrame(page).getByLabel("Piece row", { exact: true });
  const column = productFrame(page).getByLabel("Piece column", { exact: true });
  await expect(game).toBeVisible();
  const rowBefore = await numericText(row);
  await expect.poll(async () => numericText(row), { timeout: 8_000 })
    .not.toBe(rowBefore);
  const columnBefore = await numericText(column);
  await game.focus();
  await game.press("ArrowLeft");
  await expect.poll(async () => numericText(column), { timeout: 3_000 })
    .not.toBe(columnBefore);

  await productFrame(page).getByRole("button", {
    name: "Back to games",
    exact: true
  }).click();
  await productFrame(page).getByRole("button", {
    name: "Play Snake",
    exact: true
  }).click();
  await expect(productFrame(page).getByLabel("Snake game", { exact: true }))
    .toBeVisible();
  await expect(productFrame(page).getByRole("region", {
    name: "Snake ranking",
    exact: true
  })).toContainText("Player One");
  await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
    .toBeVisible();
  return {
    "tetris-listed-and-playable": true,
    "tetris-timer-advanced": true,
    "tetris-keyboard-moved": true,
    "snake-preserved": true,
    "ranking-preserved": true,
    "account-preserved": true
  };
}

async function verifyScenario(scenario, page, sessionId, version) {
  switch (scenario) {
    case "DEMO-01":
      return verifySnake(page);
    case "DEMO-02":
      return verifyAccountShell(page);
    case "DEMO-03":
      return verifyAccountExperience(page, sessionId, version);
    case "DEMO-04":
      return verifyRanking(page, sessionId, version);
    case "DEMO-05":
      return verifyGameLibrary(page);
    case "DEMO-06":
      return verifyTetrisAddition(page);
    case "PUBLIC-02":
      return verifyPublicRanking(page, sessionId, version);
    case "PUBLIC-03":
      return verifyPublicRankingRule(page, sessionId, version);
    case "PUBLIC-04":
      return verifyPublicGameLibrary(page);
    case "PUBLIC-05":
      return verifyPublicTetrisAddition(page);
    default:
      throw new Error(`Unsupported demo scenario ${scenario}`);
  }
}

async function verifyWithRepair(scenario, initialTurn, page, sessionId) {
  const before = initialTurn.before;
  let after = initialTurn.after;
  let duration = initialTurn.duration;
  let completedAtMs = initialTurn["completed-at-ms"];
  let repairCount = Math.max(0, after.version - before.version - 1);

  while (true) {
    try {
      const outcomes = await verifyScenario(
        scenario,
        page,
        sessionId,
        after.version
      );
      return {
        turn: {
          before,
          after,
          duration,
          "completed-at-ms": completedAtMs
        },
        outcomes: { ...outcomes, "semantic-repair-count": repairCount }
      };
    } catch (error) {
      if (repairCount >= maximumSemanticRepairs) throw error;
      const repairTurn = await submitTurn(
        page,
        `The saved ${scenario} change failed real browser outcome verification. `
        + "Repair the current product now instead of explaining. Preserve every existing feature, "
        + "the original runtime-surface constraint, stored data, and product account state. "
        + `Original outcome: ${activePrompts[scenario]} `
        + `Bounded browser feedback: ${boundedFailureFeedback(error)}`
      );
      expect(repairTurn.before.version).toBe(after.version);
      expect(repairTurn.after.version).toBeGreaterThanOrEqual(after.version);
      expect(repairTurn.after.version).toBeLessThanOrEqual(after.version + 1);
      if (repairTurn.after.version > after.version) after = repairTurn.after;
      duration += repairTurn.duration;
      completedAtMs = repairTurn["completed-at-ms"];
      repairCount += 1;
    }
  }
}

async function appendAndRequire(
  page,
  sessionId,
  records,
  scenario,
  verified,
  capture
) {
  const frameCount = await activeFrameCount(page);
  const record = {
    scenario,
    "before-version": verified.turn.before.version,
    "after-version": verified.turn.after.version,
    "duration-ms": verified.turn.duration,
    "browser-outcome": true,
    "client-stage-valid": !verified.turn.after["debug-error"],
    outcomes: verified.outcomes,
    "active-frame-count": frameCount,
    capture
  };
  records.push(record);
  writeObservations(sessionId, records);
  expect(record["after-version"]).toBeGreaterThan(record["before-version"]);
  expect(record["client-stage-valid"]).toBe(true);
  expect(frameCount).toBe(1);
}

test("real Codex evolves Snake into a persistent game platform", async ({ page }) => {
  test.setTimeout(3_600_000);
  const captureStartedAt = Date.now();

  let sessionId;
  let records;
  if (resumeExisting) {
    const observations = readObservations();
    sessionId = observations["session-id"];
    records = observations.records;
    const resumePath = publicCapture
      ? `/?test-runtime=1&session=${encodeURIComponent(sessionId)}`
      : `/?test-runtime=1&session=${encodeURIComponent(sessionId)}#access=${encodeURIComponent(accessCode)}`;
    await page.goto(resumePath, {
      waitUntil: "domcontentloaded",
      timeout: 30_000
    });
    if (publicCapture) {
      const signIn = page.getByRole("region", { name: "Workspace sign in" });
      if (await signIn.isVisible().catch(() => false)) {
        await signIn.getByLabel("Password").fill(publicPassword);
        await signIn.getByRole("button", { name: "Continue" }).click();
      }
    }
    await expect.poll(async () => {
      const current = await snapshot(page);
      return current?.["session-id"] === sessionId
        && current.connection === "connected"
        && Number.isInteger(current.version);
    }, { timeout: 45_000 }).toBe(true);
  } else {
    sessionId = await createFreshSession(page);
    records = [];
  }

  while (records.length < activeScenarioOrder.length) {
    const scenario = activeScenarioOrder[records.length];
    const scenarioStartedAt = Date.now();
    const recordedVersion = records.at(-1)?.["after-version"] ?? 0;
    const current = await snapshot(page);
    let initialTurn;

    if (current.version > recordedVersion) {
      initialTurn = {
        before: { version: recordedVersion },
        after: current,
        duration: 0,
        "completed-at-ms": Date.now()
      };
    } else {
      initialTurn = await submitTurn(page, activePrompts[scenario]);
    }

    const verified = await verifyWithRepair(
      scenario,
      initialTurn,
      page,
      sessionId
    );
    await appendAndRequire(page, sessionId, records, scenario, verified, {
      "scenario-start-ms": scenarioStartedAt - captureStartedAt,
      "generation-complete-ms": verified.turn["completed-at-ms"]
        - captureStartedAt,
      "verification-complete-ms": Date.now() - captureStartedAt
    });
  }
});
