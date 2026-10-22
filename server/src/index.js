import "dotenv/config";
import { createHub } from "./app.js";
const hub = createHub();
hub.server.listen(hub.config.port, "127.0.0.1", () =>
  console.log(`Car Remote Hub: http://127.0.0.1:${hub.config.port}`),
);
for (const signal of ["SIGINT", "SIGTERM"])
  process.once(signal, () => {
    hub.close();
  });
