import { expect } from "@playwright/test";

export function productFrame(page) {
  return page.frameLocator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  );
}

export async function activeFrameChannel(page) {
  return page.locator(
    "iframe[data-ppp-runtime-frame]:not(.ppp-runtime-frame-staged)"
  ).getAttribute("data-ppp-runtime-channel");
}

export async function openConversation(page) {
  const button = page.getByRole("button", {
    name: "Open product conversation"
  });
  await expect(button).toBeVisible();
  await button.click();
  await expect(
    productFrame(page).getByRole("complementary", {
      name: "Product conversation"
    })
  ).toBeVisible();
  return productFrame(page);
}

export async function sendTurn(page, prompt, scope = productFrame(page)) {
  const composer = scope.getByRole("textbox", { name: "Message" });
  await composer.fill(prompt);
  await scope.getByRole("button", { name: "Send" }).click();
}

export async function createFreshSession(page, scope = productFrame(page)) {
  const selector = scope.getByRole("combobox", { name: "Current session" });
  const previous = await selector.inputValue();
  await scope.getByRole("button", { name: "New session" }).click();
  await expect.poll(() => selector.inputValue()).not.toBe(previous);
  const selected = await selector.inputValue();
  await expect(page).toHaveURL(new RegExp(`session=${selected}`));
  return selected;
}
