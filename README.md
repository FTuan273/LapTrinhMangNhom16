# Hệ thống Đặt đồ ăn trực tuyến (Web Food) - Kênh bảo mật SSL/TLS

> **Đồ án môn học:** Lập trình mạng - Nhóm 16  
> **Đề tài:** Hệ thống Đặt đồ ăn trực tuyến (Web Food) sử dụng Giao thức giao tiếp tùy chỉnh qua kênh bảo mật SSL/TLS.

---

## 1. Giới thiệu đề tài

Hệ thống **Web Food** được phát triển theo kiến trúc phân tán, tập trung giải quyết bài toán lập trình mạng và bảo mật luồng dữ liệu giao dịch thương mại điện tử theo thời gian thực (real-time). 

Hệ thống bao gồm 3 thành phần chính:
1. **Core Security Server (Java):** Lõi kỹ thuật chính quản lý `SSLServerSocket`, thực hiện bắt tay TLSv1.2, giải mã khung tin giao thức tùy chỉnh, quản lý phiên kết nối liên tục (Persistent Connection) và broadcast đơn hàng real-time.
2. **Admin Dashboard (Java Client):** Ứng dụng quản trị kết nối an toàn qua `SSLSocket`, nạp `truststore.jks`, duy trì Heartbeat (PING/PONG) và tiếp nhận thông báo đơn hàng thời gian thực.
3. **Web Backend (Node.js Client):** Mô phỏng máy chủ web tiếp nhận đơn từ khách hàng và chuyển tiếp bảo mật sang Java Core Server qua socket TLS có nạp chứng chỉ CA.

---

## 2. Các giao thức và công nghệ lõi

* **TCP qua SSL/TLS (SSLSocket / SSLServerSocket):** Mã hóa toàn bộ dữ liệu trao đổi giữa Web Backend, Core Security Server và Admin Dashboard. Tự sinh cặp khóa và chứng chỉ số (Certificate) bằng `keytool`, quản lý qua `keystore.jks` / `truststore.jks` và nạp vào `SSLContext`.
* **Custom Protocol (Giao thức tùy chỉnh đóng khung JSON):** Giao thức tự định nghĩa chạy trên nền SSLSocket với cấu trúc: `[4-byte Header độ dài (Big-Endian)] + [Payload JSON (UTF-8)]`. Cơ chế Framing chống hiện tượng dồn gói tin (TCP stream chunking / fragmentation).
* **Quản lý đa luồng & Tối ưu kết nối mạng:** Sử dụng `ExecutorService` (Thread Pool) để xử lý đa luồng kết nối đồng thời. Duy trì các kết nối thường trực (Persistent Connection) kết hợp cơ chế Heartbeat Ping/Pong định kỳ (5 giây).
* **Broadcast Real-time:** Khi Web Client đẩy đơn hàng mới (`ORDER_REQ`), Core Server lập tức phát sóng thông báo (`NEW_ORDER_NOTIFICATION`) tới tất cả các Admin Dashboard đang kết nối.

---

## 3. Bảng Ma Trận Đóng Góp Của Các Thành Viên

| MSSV | Họ và Tên | Công việc đã thực hiện | Merge Requests |
| :--- | :--- | :--- | :--- |
| **2280603576** | **Phan Anh Tuấn** | - Khởi tạo kiến trúc `CoreServer`<br>- Sinh chứng chỉ số & nạp `SSLContext` (TLSv1.2) với `keystore.jks`/`truststore.jks`<br>- Quản lý phiên kết nối đa luồng (`Session Management`)<br>- Tích hợp `ProtocolHandler`, Router xử lý thông điệp (`ORDER_REQ`, `STATUS_UPDATE`, `PING/PONG`)<br>- Định tuyến & Broadcast đơn hàng thời gian thực (Real-time) | !4, !6, !12 |
| **2280603386** | **Nguyễn Võ Minh Triết** | - Xây dựng ứng dụng `AdminClient`<br>- Cấu hình `SSLSocket` Client nạp TrustStore an toàn<br>- Xây dựng luồng Heartbeat gửi định kỳ gói tin `PING` (5 giây) duy trì kết nối persistent | !10 |
| **228060...** | **Nguyễn ... Triều** | - Xây dựng Module `ProtocolHandler` (Framing 4-byte header + JSON payload chống TCP chunking)<br>- Viết bộ kiểm thử Unit Test `ProtocolHandlerTest`<br>- Xây dựng Node.js TLS Client (`client.js`) | !8 |

---

## 4. Cấu trúc thư mục dự án

```text
LapTrinhMangNhom16/
├── certs/                      # Thư mục lưu trữ chứng chỉ số
│   ├── keystore.jks            # Khóa riêng & chứng chỉ Server
│   ├── truststore.jks          # Chứng chỉ tin cậy của Client
│   └── server.cer              # Chứng chỉ công khai xuất cho Node.js Client
├── CoreServer/                 # Module Server Java (Lõi bảo mật)
│   ├── src/main/java/com/mycompany/coreserver/
│   │   ├── CoreServer.java         # Lõi Server SSL đa luồng & Broadcast
│   │   ├── ProtocolHandler.java    # Xử lý đóng/giải khung gói tin (Framing)
│   │   └── ProtocolHandlerTest.java# Kiểm thử logic Framing & TLS demo
│   └── pom.xml
├── admin-dashboard/            # Module Client Quản trị (Java Desktop)
│   ├── src/main/java/com/mycompany/admin/dashboard/
│   │   └── AdminClient.java        # Client SSLSocket & Heartbeat Ping/Pong
│   └── pom.xml
├── client.js                   # Node.js TLS Client kiểm thử kết nối
├── test_order.js               # Mô phỏng Web Backend gửi đơn hàng ORDER_REQ
├── .gitignore                  # Cấu hình bỏ qua file rác / file build
└── README.md                   # Tài liệu dự án và ma trận đóng góp
```

---

## 5. Hướng dẫn chạy thử nghiệm hệ thống

### Bước 1: Khởi động Java Core Security Server
Từ thư mục gốc của repository:
```bash
# Biên dịch và chạy CoreServer
javac -d CoreServer/target/classes -cp CoreServer/src/main/java CoreServer/src/main/java/com/mycompany/coreserver/*.java
java -cp CoreServer/target/classes com.mycompany.coreserver.CoreServer
```
*Server sẽ lắng nghe kênh bảo mật TLS tại port: `8888`.*

### Bước 2: Khởi động Admin Dashboard (Java Client)
Mở một cửa sổ dòng lệnh (Terminal) thứ hai:
```bash
# Biên dịch và chạy AdminClient
javac -d admin-dashboard/target/classes admin-dashboard/src/main/java/com/mycompany/admin/dashboard/AdminClient.java
java -cp admin-dashboard/target/classes com.mycompany.admin.dashboard.AdminClient
```
*AdminClient sẽ bắt tay TLS với Server, nhận diện vai trò ADMIN và gửi Heartbeat PING định kỳ mỗi 5s.*

### Bước 3: Mô phỏng Web Backend đặt đơn hàng qua TLS
Mở một cửa sổ dòng lệnh thứ ba:
```bash
# Gửi gói tin ORDER_REQ qua TLS bằng Node.js
node test_order.js
```
*Quan sát:*
1. Web Client nhận phản hồi `ORDER_RESP` xác nhận thành công.
2. `AdminClient` tại Terminal 2 ngay lập tức nhận được thông báo phát sóng `NEW_ORDER_NOTIFICATION` chứa đầy đủ chi tiết đơn hàng theo thời gian thực.
