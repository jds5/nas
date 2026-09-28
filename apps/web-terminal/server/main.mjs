import { createApp } from './app.mjs';
import { accessConfig } from './auth.mjs';
const config = accessConfig(process.env);
if (!config) console.warn('access_config_invalid: all requests denied');
const app = createApp({ config });
app.server.listen(3000, '0.0.0.0', () => console.log('web_terminal_listening'));
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { app.shutdown(); });
