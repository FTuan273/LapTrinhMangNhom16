/**
 * demo_orders.js - Mô phỏng Web Backend gửi nhiều đơn hàng để demo Admin Dashboard
 *
 * Cách chạy (tại thư mục gốc LapTrinhMangNhom16, sau khi CoreServer đã chạy):
 *   node demo_orders.js            -> gửi 5 đơn, mỗi đơn cách nhau 2 giây
 *   node demo_orders.js 10 1000    -> gửi 10 đơn, cách nhau 1000 ms
 *   node demo_orders.js 1 0 <IP>   -> gửi 1 đơn tới server ở địa chỉ IP khác
 *   node demo_orders.js 3 2000 <host>:<port> -> server ở cổng khác (vd qua tunnel)
 *   node demo_orders.js 1 0 127.0.0.1 --plain
 *                                  -> gửi qua TCP THUẦN cổng 9999 tới PlainTextDemoServer
 *                                     (KHÔNG mã hóa, chỉ để đối chứng trong Wireshark)
 *
 * Điểm kỹ thuật:
 *  - MỘT kết nối TLS thường trực cho tất cả đơn (Persistent Connection).
 *  - XÁC THỰC chứng chỉ server bằng certs/server.cer (rejectUnauthorized: true).
 *  - Đóng khung 4 byte Big-Endian + JSON, đọc phản hồi có xử lý dồn/phân mảnh gói.
 */
const tls = require('tls');
const net = require('net');
const fs = require('fs');
const path = require('path');

const PLAIN = process.argv.includes('--plain');
const ARGS = process.argv.slice(2).filter(a => !a.startsWith('--'));
const COUNT = parseInt(ARGS[0] || '5', 10);
const DELAY_MS = parseInt(ARGS[1] || '2000', 10);
// Tham số thứ 3 có thể là "host" hoặc "host:port" (vd khi dùng tunnel: abc.a.free.pinggy.link:41234)
const [HOST, PORT_ARG] = (ARGS[2] || '127.0.0.1').split(':');
const PORT = PORT_ARG ? parseInt(PORT_ARG, 10) : (PLAIN ? 9999 : 8888);

// server.cer do keytool xuất ở dạng DER (nhị phân); Node cần PEM -> tự chuyển
function loadCaPem() {
  const pemPath = path.join(__dirname, 'certs', 'server.pem');
  if (fs.existsSync(pemPath)) return fs.readFileSync(pemPath);
  const der = fs.readFileSync(path.join(__dirname, 'certs', 'server.cer'));
  if (der.toString('latin1').startsWith('-----BEGIN')) return der;
  const b64 = der.toString('base64').match(/.{1,64}/g).join('\n');
  return Buffer.from(`-----BEGIN CERTIFICATE-----\n${b64}\n-----END CERTIFICATE-----\n`);
}

const MENU = [
  { name: 'Cơm tấm sườn nướng', price: 45000 },
  { name: 'Phở bò tái', price: 55000 },
  { name: 'Bún chả Hà Nội', price: 50000 },
  { name: 'Bánh mì thịt', price: 25000 },
  { name: 'Trà sữa trân châu', price: 30000 },
  { name: 'Cà phê sữa đá', price: 20000 },
];
const CUSTOMERS = [
  ['Nguyễn Văn An', '0901234567', '12 Nguyễn Huệ, Quận 1'],
  ['Trần Thị Bình', '0987654321', '789 Điện Biên Phủ, Bình Thạnh'],
  ['Lê Hoàng Cường', '0912345678', '45 Lê Lợi, Gò Vấp'],
  ['Phạm Minh Duy', '0934567890', '23 Võ Văn Ngân, Thủ Đức'],
  ['Vũ Thu Hà', '0976543210', '101 Cách Mạng Tháng 8, Quận 3'],
];
const PAY = ['COD', 'MOMO', 'ZALOPAY', 'BANKING'];

function makeOrder(i) {
  const [customer, phone, address] = CUSTOMERS[i % CUSTOMERS.length];
  const items = [];
  const n = 1 + (i % 3);
  for (let k = 0; k < n; k++) {
    const m = MENU[(i + k * 2) % MENU.length];
    items.push({ name: m.name, quantity: 1 + ((i + k) % 2), price: m.price });
  }
  return {
    type: 'ORDER_REQ',
    orderId: 'ORD-' + Date.now().toString().slice(-6) + '-' + (i + 1),
    customer, phone, address, items,
    total: items.reduce((s, it) => s + it.quantity * it.price, 0),
    paymentMethod: PAY[i % PAY.length],
    timestamp: new Date().toISOString(),
  };
}

function frame(obj) {
  const payload = Buffer.from(JSON.stringify(obj), 'utf8');
  const header = Buffer.alloc(4);
  header.writeUInt32BE(payload.length, 0);
  return Buffer.concat([header, payload]);
}

async function sendAll() {
  if (PLAIN) {
    // TCP thuần — socket là net.Socket, không có getProtocol()/getCipher()
    console.log(`[Web] !!! Đã kết nối TCP THUẦN (KHÔNG mã hóa) tới ${HOST}:${PORT} — chỉ để đối chứng Wireshark`);
  } else {
    // TLS — socket là tls.TLSSocket, có đầy đủ method TLS
    console.log(`[Web] Đã kết nối TLS tới ${HOST}:${PORT} — ${socket.getProtocol()} — ${socket.getCipher().name}`);
    console.log(`[Web] Chứng chỉ server đã được xác thực: ${socket.authorized}`);
  }
  for (let i = 0; i < COUNT; i++) {
    const o = makeOrder(i);
    socket.write(frame(o));
    console.log(`[Web -> Server] ${o.orderId} | ${o.customer} | ${o.total.toLocaleString('vi-VN')}đ`);
    if (i < COUNT - 1) await new Promise(r => setTimeout(r, DELAY_MS));
  }
  setTimeout(() => socket.end(), 1000);
}

const socket = PLAIN
  ? net.connect(PORT, HOST, sendAll)
  : tls.connect({
      host: HOST, port: PORT, ca: loadCaPem(), rejectUnauthorized: true,
      // Chứng chỉ hiện tại chưa có SAN -> bỏ qua kiểm tra tên máy chủ, VẪN kiểm tra chuỗi tin cậy.
      // Sau khi sinh lại chứng chỉ có SAN (-ext SAN=...) thì XÓA dòng dưới.
      checkServerIdentity: () => undefined,
    }, sendAll);

let buf = Buffer.alloc(0);
let ok = 0;
socket.on('data', chunk => {
  buf = Buffer.concat([buf, chunk]);
  while (buf.length >= 4) {
    const len = buf.readUInt32BE(0);
    if (buf.length < 4 + len) break;
    const msg = JSON.parse(buf.subarray(4, 4 + len).toString('utf8'));
    buf = buf.subarray(4 + len);
    if (msg.type === 'ORDER_RESP') {
      ok++;
      console.log(`[Server -> Web] ORDER_RESP ${msg.orderId || ''}: ${msg.status}`);
    }
  }
});
socket.on('error', e => console.error('[Web] Lỗi:', e.code || '', e.message));
socket.on('close', () => console.log(`[Web] Đóng kết nối. Server xác nhận ${ok}/${COUNT} đơn.`));
