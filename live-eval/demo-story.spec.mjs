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
  "PUBLIC-01": `Turn this blank product into a polished dark arcade welcome screen without a refresh. Use a near-black canvas, high-contrast text, restrained neon accents, and responsive spacing. Give the main region the accessible name "Dark arcade" and add the heading "Night Shift Arcade". Keep the product conversation usable. This is a client-only visual change: do not write server, shared-domain, test, or migration files.`,
  "PUBLIC-02": `Add a polished Snake game to this dark arcade. It must move automatically from a browser timer, use a keyboard-focusable root named "Snake game", handle real ArrowUp, ArrowDown, ArrowLeft, and ArrowRight input without reload, and show values named "Snake head row", "Snake head column", "Snake direction", and "Snake score". Preserve the dark theme. Keep all game logic in the browser sandbox and do not write server, shared-domain, test, or migration files.`,
  "PUBLIC-03": `Add real product signup and sign-in to this Snake arcade while keeping Snake playable. Use the provided product-auth capabilities, not PPP workspace access, and never store passwords yourself. Signup needs fields named "Display name", "Sign-in ID", and "Password", a button named "Create account", and a switch named "Have an account? Sign in". Login needs "Sign-in ID", "Password", a button named "Sign in", and a switch named "Need an account? Create one". After signup or sign-in show "Signed in as <display name>" and "Sign out". Store the public display-name profile in a host-approved migration keyed by auth user id, restore current-user state on reload, and add minimal rollback-only domain tests.`,
  "PUBLIC-04": `Make the arcade account panel cohesive and reliable. Render exactly one of signup, sign-in, or signed-in state at a time. Show signed-in state only when a successful signup, sign-in, or current-user action actually returns a user; never infer authentication from a pending display name or preserved local form state. Sign out must call the existing server action and return to signup. Put every invalid input, duplicate account, wrong password, and server failure in an output named "Account message". The third argument of runtime.api/action! now stores a bounded {:error string :code keyword} failure at [:runtime/action-errors target-key] and also at an empty target key, so render that real value and clear pending copy. Restore signed-in state from current-user after reload. Preserve playable Snake, the dark theme, every existing server action, migration, and domain rule. This is a client-only presentation and error-handling change.`,
  "PUBLIC-05": `Turn this into a two-game arcade while preserving the signed-in account and playable Snake. Add a home view headed "Game library" with buttons named "Play Snake" and "Play Tetris". Tetris must advance automatically from a browser timer, accept ArrowLeft, ArrowRight, and ArrowDown without reload, use a keyboard-focusable root named "Tetris game", and expose values named "Piece row" and "Piece column". Both games need a "Back to games" button. Preserve the dark theme and every existing server capability. This is client-only: do not write server, shared-domain, test, or migration files.`
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
  "PUBLIC-01",
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
  await composer.scrollIntoViewIfNeeded();
  await composer.focus();
  if (publicCapture) {
    await composer.pressSequentially(prompt, { delay: 2 });
  } else {
    await composer.fill(prompt);
  }
  await composer.press("Enter");
  let terminalFailure = null;
  await expect.poll(async () => {
    if (publicCapture) {
      const articles = conversation.getByRole("article");
      if (await articles.count()) {
        await articles.last().scrollIntoViewIfNeeded().catch(() => {});
      }
    }
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
  if (publicCapture) {
    const articles = conversation.getByRole("article");
    if (await articles.count()) {
      await articles.last().scrollIntoViewIfNeeded().catch(() => {});
      await page.waitForTimeout(900);
    }
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

async function verifyPublicDarkTheme(page) {
  await closeConversation(page);
  const darkArcade = productFrame(page).getByRole("main", {
    name: "Dark arcade",
    exact: true
  });
  await expect(darkArcade).toBeVisible();
  await expect(productFrame(page).getByRole("heading", {
    name: "Night Shift Arcade",
    exact: true
  })).toBeVisible();
  const background = await darkArcade.evaluate((node) =>
    getComputedStyle(node).backgroundColor
  );
  const channels = background.match(/[\d.]+/g)?.slice(0, 3).map(Number) || [];
  expect(channels).toHaveLength(3);
  expect(channels.reduce((sum, value) => sum + value, 0)).toBeLessThan(240);
  return {
    "dark-theme-visible": true,
    "dark-arcade-heading-visible": true,
    "client-only-theme-change": true
  };
}

async function verifyPublicAccountExperience(page, sessionId, version) {
  const evidence = {};
  const accountId = `player-${sessionId.slice(0, 8)}`;
  const accountPassword = "arcade password";
  const waitForTerminalMessage = async (accountMessage, previous) => {
    let terminal = "";
    await expect.poll(async () => {
      const value = String(await accountMessage.textContent()).trim();
      if (!value || value === String(previous || "").trim()) return false;
      if (/creating|signing|checking|loading|please wait|working/i.test(value)) {
        return false;
      }
      terminal = value;
      return true;
    }, { timeout: 20_000 }).toBe(true);
    return terminal;
  };
  try {
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
    await expect(displayName).toBeVisible();
    await expect(identifier).toBeVisible();
    await expect(password).toBeVisible();

    await displayName.fill("");
    await identifier.fill("");
    await password.fill("");
    await displayName.pressSequentially("Player One", { delay: 35 });
    await identifier.pressSequentially("x", { delay: 35 });
    await password.pressSequentially(accountPassword, { delay: 20 });
    const invalidBefore = await accountMessage.textContent();
    await productFrame(page).getByRole("button", {
      name: "Create account",
      exact: true
    }).click();
    evidence.invalid = await waitForTerminalMessage(
      accountMessage,
      invalidBefore
    );
    expect(evidence.invalid).toMatch(/valid|identifier|sign-in/i);

    await identifier.fill("");
    await identifier.pressSequentially(accountId, { delay: 12 });
    const signupBefore = await accountMessage.textContent();
    await productFrame(page).getByRole("button", {
      name: "Create account",
      exact: true
    }).click();
    evidence.signup = await waitForTerminalMessage(accountMessage, signupBefore);

    if (/already|taken|in use/i.test(evidence.signup)) {
      await productFrame(page).getByRole("button", {
        name: "Have an account? Sign in",
        exact: true
      }).click();
      const resumedId = productFrame(page).getByRole("textbox", {
        name: "Sign-in ID",
        exact: true
      });
      const resumedPassword = productFrame(page).getByLabel("Password", {
        exact: true
      });
      await resumedId.fill("");
      await resumedPassword.fill("");
      await resumedId.pressSequentially(accountId, { delay: 12 });
      await resumedPassword.pressSequentially(accountPassword, { delay: 20 });
      const resumeBefore = await accountMessage.textContent();
      await productFrame(page).getByRole("button", {
        name: "Sign in",
        exact: true
      }).click();
      evidence.resumeLogin = await waitForTerminalMessage(
        accountMessage,
        resumeBefore
      );
      expect(evidence.resumeLogin).not.toMatch(/not match|invalid|failed/i);
    } else {
      expect(evidence.signup).not.toMatch(/invalid|failed|could not/i);
    }

    await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
      .toBeVisible({ timeout: 20_000 });
    await page.waitForTimeout(900);

    await productFrame(page).getByRole("button", {
      name: "Sign out",
      exact: true
    }).click();
    const signInSwitch = productFrame(page).getByRole("button", {
      name: "Have an account? Sign in",
      exact: true
    });
    await expect(signInSwitch).toBeVisible({ timeout: 20_000 });
    await signInSwitch.click();
    const signInId = productFrame(page).getByRole("textbox", {
      name: "Sign-in ID",
      exact: true
    });
    const signInPassword = productFrame(page).getByLabel("Password", {
      exact: true
    });
    await signInId.fill("");
    await signInPassword.fill("");
    await signInId.pressSequentially(accountId, { delay: 12 });
    await signInPassword.pressSequentially(accountPassword, { delay: 20 });
    const loginBefore = await accountMessage.textContent();
    await productFrame(page).getByRole("button", {
      name: "Sign in",
      exact: true
    }).click();
    evidence.login = await waitForTerminalMessage(accountMessage, loginBefore);
    expect(evidence.login).not.toMatch(/not match|invalid|failed/i);
    await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
      .toBeVisible({ timeout: 20_000 });
    await page.waitForTimeout(900);

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
  } catch (error) {
    throw new Error(
      `Product account verification failed with bounded action evidence ${JSON.stringify(evidence)}. `
      + boundedFailureFeedback(error)
    );
  }
}

async function verifyPublicSnakeInDarkTheme(page) {
  const snake = await verifySnake(page);
  await expect(productFrame(page).getByRole("main", {
    name: "Dark arcade",
    exact: true
  })).toBeVisible();
  return { ...snake, "dark-theme-preserved": true };
}

async function verifyPublicSnakeWithAccount(page) {
  const snake = await verifySnake(page);
  await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
    .toBeVisible();
  return {
    ...snake,
    "signed-in-account-preserved": true
  };
}

async function verifyPublicArcadeLibrary(page) {
  await closeConversation(page);
  const library = productFrame(page).getByRole("heading", {
    name: "Game library",
    exact: true
  });
  await expect(library).toBeVisible();
  await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
    .toBeVisible();
  await expect(productFrame(page).getByRole("button", {
    name: "Play Snake",
    exact: true
  })).toBeVisible();
  await productFrame(page).getByRole("button", {
    name: "Play Tetris",
    exact: true
  }).click();

  const tetris = productFrame(page).getByLabel("Tetris game", { exact: true });
  const row = productFrame(page).getByLabel("Piece row", { exact: true });
  const column = productFrame(page).getByLabel("Piece column", { exact: true });
  await expect(tetris).toBeVisible();
  const rowBefore = await numericText(row);
  await expect.poll(async () => numericText(row), { timeout: 8_000 })
    .not.toBe(rowBefore);
  const columnBefore = await numericText(column);
  await tetris.focus();
  await tetris.press("ArrowLeft");
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
  await expect(productFrame(page).getByText(/Signed in as Player One/i).first())
    .toBeVisible();
  return {
    "game-library-visible": true,
    "tetris-listed-and-playable": true,
    "tetris-timer-advanced": true,
    "tetris-keyboard-moved": true,
    "snake-preserved": true,
    "signed-in-account-preserved": true
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
    const transitioned = await expect.poll(async () =>
      productFrame(page).getByRole("button", {
        name: "Create account",
        exact: true
      }).isVisible().catch(() => false), { timeout: 20_000 })
      .toBe(true)
      .then(() => true)
      .catch(() => false);
    if (!transitioned) {
      throw new Error(
        "The UI claimed a signed-in user, but Sign out did not reach the server-backed signup state. "
        + "Treat current-user and successful auth action responses as the only authentication source of truth; "
        + "remove optimistic pending-display-name or preserved-form fallbacks and wire the existing sign-out action."
      );
    }
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
    case "PUBLIC-01":
      return verifyPublicDarkTheme(page);
    case "PUBLIC-02":
      return verifyPublicSnakeInDarkTheme(page);
    case "PUBLIC-03":
      return verifyAccountShell(page);
    case "PUBLIC-04":
      return verifyPublicAccountExperience(page, sessionId, version);
    case "PUBLIC-05":
      return verifyPublicArcadeLibrary(page);
    default:
      throw new Error(`Unsupported demo scenario ${scenario}`);
  }
}

async function verifyWithRepair(scenario, initialTurn, page, sessionId) {
  const before = initialTurn.before;
  let after = initialTurn.after;
  let duration = initialTurn.duration;
  let completedAtMs = initialTurn["completed-at-ms"];
  // Runtime versions may already be ahead when a previously committed turn is
  // resumed after a capture failure. That version distance is not the number of
  // semantic repairs attempted by this verification run.
  let repairCount = 0;

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

test("real Codex evolves a dark arcade with product accounts and games", async ({ page }) => {
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
