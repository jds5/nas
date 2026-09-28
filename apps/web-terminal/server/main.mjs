import { UploadStore } from './uploads.mjs';
import { createApp } from './app.mjs';
import { accessConfig } from './auth.mjs';
const config = accessConfig(process.env);
if (!config) console.warn('access_config_invalid: all requests denied');
const app = createApp({ config, uploads: new UploadStore('/uploads', process.env.UPLOAD_HOST_DIR || '/home/yao/.nas-web-uploads') });
app.server.listen(3000, '0.0.0.0', () => console.log('web_terminal_listening'));
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { app.shutdown(); });
