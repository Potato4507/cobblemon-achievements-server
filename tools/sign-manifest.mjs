import { readFileSync, writeFileSync } from "node:fs";
import { createPrivateKey, sign } from "node:crypto";

const [, , inputPath = "remote/manifest.payload.json", outputPath = "remote/manifest.signed.json"] = process.argv;
const privateKeyPem = process.env.COBBLE_ACHIEVEMENTS_MANIFEST_PRIVATE_KEY_PEM;

if (!privateKeyPem) {
  console.error("Set COBBLE_ACHIEVEMENTS_MANIFEST_PRIVATE_KEY_PEM to an Ed25519 PKCS#8 private key PEM.");
  process.exit(1);
}

const payload = readFileSync(inputPath);
const privateKey = createPrivateKey(privateKeyPem.replaceAll("\\n", "\n"));
const signature = sign(null, payload, privateKey);

const envelope = {
  payloadBase64: payload.toString("base64"),
  signatureBase64: signature.toString("base64")
};

writeFileSync(outputPath, `${JSON.stringify(envelope, null, 2)}\n`);
