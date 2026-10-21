/**
 * test_order.js - Mô phỏng Web Backend (Node.js) gửi đơn hàng ORDER_REQ tới CoreServer
 */
const tls = require('tls');
const fs = require('fs');
const path = require('path');

const HOST = 'localhost';
const PORT = 8888;

const certPath = path.resolve(__dirname, 'certs/server.cer');
const tlsOptions = {
    host: HOST,
    port: PORT,
    ca: fs.existsSync(certPath) ? [fs.readFileSync(certPath)] : undefined,
    rejectUnauthorized: false
};

console.log('[Web Client] Đang kết nối TLS tới CoreServer ' + HOST + ':' + PORT + '...');
const client = tls.connect(tlsOptions, () => {
    console.log('[Web Client] Đã kết nối TLS thành công!');

    const orderPayload = {
        type: "ORDER_REQ",
        orderId: "ORD-" + Date.now(),
        customer: "Phan Anh Tuấn",
        phone: "0987654321",
        address: "789 Điện Biên Phủ, Bình Thạnh, TP.HCM",
        items: [
            { name: "Cơm tấm sườn nướng", quantity: 2, price: 45000 },
            { name: "Trà sữa trân châu hoàng kim", quantity: 2, price: 30000 }
        ],
        total: 150000,
        paymentMethod: "MOMO",
        timestamp: new Date().toISOString()
    };

    const jsonString = JSON.stringify(orderPayload);
    const payloadBuffer = Buffer.from(jsonString, 'utf-8');

    // Đóng khung 4 byte Big-Endian
    const headerBuffer = Buffer.alloc(4);
    headerBuffer.writeUInt32BE(payloadBuffer.length, 0);
    const packet = Buffer.concat([headerBuffer, payloadBuffer]);

    console.log('[Web Client -> CoreServer] Đẩy đơn hàng bảo mật: ' + jsonString);
    client.write(packet);
});

let accumulatedBuffer = Buffer.alloc(0);
client.on('data', (chunk) => {
    accumulatedBuffer = Buffer.concat([accumulatedBuffer, chunk]);
    while (accumulatedBuffer.length >= 4) {
        const payloadLength = accumulatedBuffer.readUInt32BE(0);
        if (accumulatedBuffer.length >= 4 + payloadLength) {
            const payloadBuffer = accumulatedBuffer.subarray(4, 4 + payloadLength);
            const responseText = payloadBuffer.toString('utf-8');
            console.log('\n[Web Client <- CoreServer] Nhận phản hồi xác nhận đơn:');
            console.log(responseText);
            accumulatedBuffer = accumulatedBuffer.subarray(4 + payloadLength);
            setTimeout(() => client.end(), 300);
        } else {
            break;
        }
    }
});

client.on('end', () => {
    console.log('[Web Client] Hoàn tất phiên đặt hàng.');
});
