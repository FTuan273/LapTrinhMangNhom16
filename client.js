/**
 * client.js - Node.js TLS Client tối giản giao tiếp với Java CoreServer
 * Phụ trách: Triều (Java Core: Stream Parsing & Custom Protocol)
 * 
 * Yêu cầu:
 * - Dùng module tls để kết nối tới Java CoreServer qua cổng bảo mật TLS.
 * - Đóng khung giao thức: 4-byte Big-Endian (độ dài payload) + chuỗi JSON (UTF-8).
 * - Đẩy thẳng dữ liệu vào Java Server và nhận phản hồi.
 */

const tls = require('tls');
const fs = require('fs');
const path = require('path');

const HOST = 'localhost';
const PORT = 8888;

// Kiểm tra chứng chỉ server nếu có (nếu không có chứng chỉ CA thì tắt rejectUnauthorized để dev test)
const certPath = path.resolve(__dirname, 'certs/server.cer');
const tlsOptions = {
    host: HOST,
    port: PORT,
    ca: fs.existsSync(certPath) ? [fs.readFileSync(certPath)] : undefined,
    rejectUnauthorized: false // Cho phép chứng chỉ tự ký (self-signed) trong môi trường phát triển
};

console.log(`[TLS Client] Đang kết nối tới ${HOST}:${PORT}...`);

// 1. DÙNG MODULE TLS KẾT NỐI VÀO JAVA SERVER
const client = tls.connect(tlsOptions, () => {
    console.log('[TLS Client] Kết nối TLS thành công!');
    console.log('[TLS Client] Cipher:', client.getCipher().name);

    // Dữ liệu JSON cần gửi
    const payloadObject = {
        sender: "Triều",
        action: "TEST_FRAMING",
        message: "Xin chào Java Server! Gói tin kiểm thử Framing từ Node.js TLS Client.",
        timestamp: new Date().toISOString()
    };

    // 2. ĐÓNG KHUNG 4-BYTE + CHUỖI JSON VÀ ĐẨY THẲNG VÀO SERVER
    sendFramedMessage(client, payloadObject);
});

/**
 * Đóng khung: 4-byte header (Big-Endian độ dài) + JSON payload và đẩy qua socket
 */
function sendFramedMessage(socket, data) {
    const jsonString = typeof data === 'string' ? data : JSON.stringify(data);
    const payloadBuffer = Buffer.from(jsonString, 'utf-8');

    // Tạo header 4 byte Big-Endian biểu thị kích thước payload
    const headerBuffer = Buffer.alloc(4);
    headerBuffer.writeUInt32BE(payloadBuffer.length, 0);

    // Ghép 4-byte header + payload thành một frame hoàn chỉnh
    const packet = Buffer.concat([headerBuffer, payloadBuffer]);

    console.log(`[TLS Client] Gửi gói tin (${payloadBuffer.length} bytes payload): ${jsonString}`);
    socket.write(packet);
}

// 3. XỬ LÝ NHẬN PHẢN HỒI TỪ JAVA SERVER (Xử lý chống chunking phía client)
let accumulatedBuffer = Buffer.alloc(0);

client.on('data', (chunk) => {
    // Tích lũy các mảnh byte nhận được từ luồng TCP
    accumulatedBuffer = Buffer.concat([accumulatedBuffer, chunk]);

    // Xử lý vòng lặp khi buffer chứa đủ ít nhất 4 byte header
    while (accumulatedBuffer.length >= 4) {
        // Đọc 4 byte đầu tiên để lấy độ dài payload
        const payloadLength = accumulatedBuffer.readUInt32BE(0);

        // Kiểm tra xem đã nhận đủ số byte của payload chưa
        if (accumulatedBuffer.length >= 4 + payloadLength) {
            // Cắt đúng số byte của payload
            const payloadBuffer = accumulatedBuffer.subarray(4, 4 + payloadLength);
            const responseText = payloadBuffer.toString('utf-8');

            console.log(`\n[TLS Client] Nhận phản hồi (${payloadLength} bytes):`);
            try {
                const parsed = JSON.parse(responseText);
                console.log(JSON.stringify(parsed, null, 2));
            } catch (e) {
                console.log(responseText);
            }

            // Cắt bỏ frame đã xử lý khỏi buffer
            accumulatedBuffer = accumulatedBuffer.subarray(4 + payloadLength);

            // Đóng kết nối sau khi nhận phản hồi hoàn tất kiểm thử
            console.log('\n[TLS Client] Hoàn tất kiểm thử thành công! Đang ngắt kết nối...');
            setTimeout(() => client.end(), 200);
        } else {
            // Chưa đủ dữ liệu, tiếp tục chờ chunk tiếp theo
            break;
        }
    }
});

client.on('end', () => {
    console.log('[TLS Client] Server đã đóng kết nối.');
});

client.on('error', (err) => {
    console.error('[TLS Client] Lỗi socket:', err.message);
});

client.on('close', () => {
    console.log('[TLS Client] Đã đóng kết nối.');
});
