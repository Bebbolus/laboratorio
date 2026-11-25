'use strict';

const byId = id => document.getElementById(id);
const form = byId('composer');
const input = byId('input');
const messages = byId('messages');
const notice = byId('notice');
const history = [];
let currentReply = null;
let listening = false;
let modelReady = false;
let nextRequestId = 0;

function showNotice(text) {
  notice.textContent = text;
  notice.hidden = !text;
}

function onionTarget(prompt) {
  if (!/\bonion\b/i.test(prompt)) return null;
  const match = prompt.match(/(?:^|[\s(<])((?:[a-z0-9-]+\.)+[a-z]{2,63})(?=$|[\s,;:!?)\]>])/i);
  if (!match) return null;
  const domain = match[1].toLowerCase();
  return domain.endsWith('.onion') ? null : domain;
}

function resizeInput() {
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 106) + 'px';
}

function addMessage(role, text) {
  byId('welcome').hidden = true;
  const row = document.createElement('article');
  row.className = 'message ' + role;
  const label = document.createElement('small');
  label.textContent = role === 'user' ? 'Tu' : 'Assistente';
  const body = document.createElement('div');
  body.textContent = text;
  row.append(label, body);
  messages.append(row);
  byId('scroll').scrollTop = byId('scroll').scrollHeight;
  return body;
}

form.addEventListener('submit', event => {
  event.preventDefault();
  const prompt = input.value.trim();
  if (!prompt || currentReply) return;
  if (/\b[a-z2-7]{16,56}\.onion\b/i.test(prompt)) {
    showNotice('');
    addMessage('user', prompt);
    addMessage('assistant', 'Non apro indirizzi .onion scritti a mano: non posso verificarne la provenienza. '
      + 'Chiedimi l’onion di un dominio, per esempio «apri l’onion di torproject.org».');
    input.value = '';
    resizeInput();
    return;
  }
  const target = onionTarget(prompt);
  if (target && window.Lab?.onion) {
    showNotice('');
    history.push({ role: 'user', content: prompt });
    addMessage('user', prompt);
    input.value = '';
    resizeInput();
    currentReply = { id: ++nextRequestId, body: addMessage('assistant', 'Verifico l’indirizzo onion dichiarato da ' + target + '…') };
    byId('send').disabled = true;
    byId('new-chat').disabled = true;
    window.Lab.onion(target);
    return;
  }
  if (!modelReady || !window.Lab) {
    showNotice('Attendi il modello locale o importalo per iniziare.');
    return;
  }
  showNotice('');
  history.push({ role: 'user', content: prompt });
  addMessage('user', prompt);
  input.value = '';
  resizeInput();
  const id = ++nextRequestId;
  currentReply = { id, body: addMessage('assistant', 'Sto elaborando…') };
  byId('send').disabled = true;
  byId('new-chat').disabled = true;
  const context = history.slice(-5).map(item => ({
    role: item.role,
    content: item.content.slice(0, item === history[history.length - 1] ? 3600 : 450)
  }));
  if (context[0]?.role === 'assistant') context.shift();
  window.Lab.ask(id, JSON.stringify(context));
});

input.addEventListener('input', resizeInput);
input.addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    form.requestSubmit();
  }
});
document.querySelectorAll('[data-prompt]').forEach(button => {
  button.addEventListener('click', () => {
    input.value = button.dataset.prompt;
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
    resizeInput();
  });
});
byId('new-chat').addEventListener('click', () => {
  if (currentReply) return;
  history.length = 0;
  messages.replaceChildren();
  byId('welcome').hidden = false;
  showNotice('');
  input.focus();
});
byId('mic').addEventListener('click', () => {
  if (!window.Lab) {
    showNotice('La dettatura offline è disponibile nell’app Android.');
    return;
  }
  if (listening) window.Lab.stopVoice();
  else window.Lab.voice();
});
byId('import').addEventListener('click', () => {
  showNotice('');
  window.Lab?.pickModel();
});
byId('retry').addEventListener('click', () => {
  showNotice('');
  window.Lab?.start();
});

window.labModel = (state, label) => {
  const status = byId('status');
  status.className = 'status' + (state === 'ready' ? ' ready' : state === 'error' ? ' error' : '');
  status.lastElementChild.textContent = label;
  modelReady = state === 'ready';
  byId('setup').hidden = !window.Lab || (state !== 'missing' && state !== 'error');
  byId('setup-label').textContent = state === 'missing'
    ? 'Carica il modello per usare la chat offline.'
    : 'Il motore locale richiede un nuovo tentativo.';
  byId('import').hidden = state !== 'missing';
  byId('retry').hidden = state !== 'error';
};
window.labOnion = (domain, onion, note) => {
  if (!currentReply) return;
  showNotice('');
  const body = currentReply.body;
  body.textContent = '';
  const head = document.createElement('div');
  head.textContent = onion
    ? 'Onion verificato per ' + domain
    : 'Nessun onion verificato per ' + domain;
  body.append(head);
  if (onion) {
    const address = document.createElement('code');
    address.textContent = onion;
    body.append(address);
    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'onion-open';
    open.textContent = 'Apri in Tor Browser';
    open.addEventListener('click', () => window.Lab.openTor(onion));
    body.append(open);
  }
  if (note) {
    const source = document.createElement('p');
    source.className = 'onion-note';
    source.textContent = note;
    body.append(source);
  }
  currentReply = null;
  byId('send').disabled = false;
  byId('new-chat').disabled = false;
  byId('scroll').scrollTop = byId('scroll').scrollHeight;
};
window.labReply = (id, ok, value) => {
  if (currentReply?.id !== id) return;
  currentReply.body.textContent = value;
  if (ok) history.push({ role: 'assistant', content: value });
  else history.pop();
  currentReply = null;
  byId('send').disabled = false;
  byId('new-chat').disabled = false;
  byId('scroll').scrollTop = byId('scroll').scrollHeight;
};
window.labVoice = (state, value) => {
  listening = state === 'listening' || state === 'preparing';
  const mic = byId('mic');
  mic.classList.toggle('listening', listening);
  mic.setAttribute('aria-label', listening ? 'Ferma dettatura' : 'Detta un messaggio');
  if (state === 'result') {
    input.value = (input.value ? input.value + ' ' : '') + value;
    input.value = input.value.slice(0, input.maxLength);
    resizeInput();
    input.focus();
  }
  showNotice(state === 'preparing' ? 'Preparo il riconoscimento vocale…' :
    listening ? 'Sto ascoltando…' : state === 'error' ? value : '');
};
window.labNotice = showNotice;
window.labTor = (percent, label) => {
  if (percent < 0) showNotice('Tor non disponibile: ' + label + '.');
  else if (percent < 100) showNotice('Tor ' + percent + '% · ' + label);
  else showNotice('Tor pronto');
};
window.labFocus = () => input.focus();

const canvas = byId('wallpaper');
const ctx = canvas.getContext('2d', { alpha: false });
const source = document.createElement('canvas');
const base = source.getContext('2d', { alpha: false });
const motion = byId('motion');
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
let paused = reducedMotion.matches;
let frame = 0;
let lastFrame = 0;
let width = 1;
let height = 1;
let stars = [];

function random(seed) {
  let state = seed;
  return () => {
    state = (state * 1664525 + 1013904223) >>> 0;
    return state / 4294967296;
  };
}

function ridge(points, fill, line) {
  base.beginPath();
  base.moveTo(0, points[0] * height);
  points.forEach((point, index) => base.lineTo(index * width / (points.length - 1), point * height));
  if (line) {
    base.strokeStyle = line;
    base.lineWidth = 1;
    base.stroke();
  }
  base.lineTo(width, height);
  base.lineTo(0, height);
  base.closePath();
  base.fillStyle = fill;
  base.fill();
}

function buildWallpaper() {
  const scale = Math.min(devicePixelRatio || 1, 1.25);
  width = source.width = canvas.width = Math.max(1, Math.round(canvas.clientWidth * scale));
  height = source.height = canvas.height = Math.max(1, Math.round(canvas.clientHeight * scale));
  const sky = base.createLinearGradient(0, 0, 0, height);
  sky.addColorStop(0, '#0b1025');
  sky.addColorStop(.53, '#252652');
  sky.addColorStop(1, '#71496a');
  base.fillStyle = sky;
  base.fillRect(0, 0, width, height);
  const moonX = width * .76;
  const moonY = height * .23;
  const moon = Math.min(width, height) * .084;
  const glow = base.createRadialGradient(moonX, moonY, moon * .2, moonX, moonY, moon * 3.4);
  glow.addColorStop(0, '#a1b4ff55');
  glow.addColorStop(1, '#a1b4ff00');
  base.fillStyle = glow;
  base.fillRect(0, 0, width, height);
  base.fillStyle = '#bac6fa';
  base.beginPath();
  base.arc(moonX, moonY, moon, 0, Math.PI * 2);
  base.fill();
  base.save();
  base.clip();
  base.fillStyle = '#8b92c870';
  for (let i = 0; i < 5; i++) base.fillRect(moonX - moon, moonY + moon * (.19 + i * .15), moon * 2, moon * .053);
  base.restore();
  ridge([.79,.76,.77,.67,.70,.62,.68,.56,.60,.54,.59,.66,.61,.69,.65,.61,.69,.70,.75,.72,.76], '#363960', '#666b99');
  ridge([.82,.77,.79,.70,.74,.66,.73,.76,.71,.77,.73,.80,.74,.70,.76,.66,.69,.60,.69,.65,.71,.73,.81], '#202b50', '#465580');
  ridge([.91,.82,.85,.79,.82,.74,.81,.83,.86,.90,.84,.87,.78,.74,.81,.78,.85,.87,.82,.88,.84,.89], '#141e3a', '#34486a');
  const foreground = base.createLinearGradient(0, height * .78, 0, height);
  foreground.addColorStop(0, '#111a34');
  foreground.addColorStop(1, '#0b1125');
  ridge([.94,.86,.90,.82,.88,.92,.85,.91,.93,.87,.94,.91,.88,.94,.86,.89,.91], foreground);
  const rng = random(2315);
  stars = Array.from({ length: 84 }, () => [rng() * width, rng() * height * .54, rng(), rng() * 8]);
  paint(0);
}

function paint(time) {
  ctx.drawImage(source, 0, 0);
  for (const [x, y, light, phase] of stars) {
    const sparkle = paused ? .65 : .63 + .27 * Math.sin(time * .00064 + phase);
    ctx.fillStyle = 'rgba(210,224,255,' + ((.18 + light * .55) * sparkle) + ')';
    ctx.fillRect(x, y, light > .87 ? 2 : 1, light > .87 ? 2 : 1);
  }
  const drift = time * .00014;
  const fogY = height * (.76 + .012 * Math.sin(drift));
  const fog = ctx.createLinearGradient(0, fogY - 18, 0, fogY + 31);
  fog.addColorStop(0, '#a095d000');
  fog.addColorStop(.52, '#aaa6e326');
  fog.addColorStop(1, '#a095d000');
  ctx.fillStyle = fog;
  ctx.fillRect(0, fogY - 18, width, 49);
}

function animate(time) {
  frame = 0;
  if (paused || document.hidden) return;
  if (time - lastFrame > 45) {
    paint(time);
    lastFrame = time;
  }
  frame = requestAnimationFrame(animate);
}

function updateMotion() {
  motion.setAttribute('aria-pressed', String(paused));
  motion.setAttribute('aria-label', paused ? 'Riprendi sfondo animato' : 'Pausa sfondo animato');
  if (frame) cancelAnimationFrame(frame);
  frame = 0;
  if (paused || document.hidden) paint(0);
  else frame = requestAnimationFrame(animate);
}
motion.addEventListener('click', () => {
  paused = !paused;
  updateMotion();
});
reducedMotion.addEventListener('change', event => {
  paused = event.matches;
  updateMotion();
});
document.addEventListener('visibilitychange', updateMotion);
addEventListener('resize', buildWallpaper);
buildWallpaper();
updateMotion();

if (window.Lab) {
  const widget = byId('home-widget');
  widget.hidden = false;
  widget.addEventListener('click', () => window.Lab.pinWidget());
  const wallpaperPicker = byId('home-wallpaper');
  wallpaperPicker.hidden = false;
  wallpaperPicker.addEventListener('click', () => window.Lab.wallpaper());
  window.Lab.start();
} else window.labModel('error', 'Anteprima');
