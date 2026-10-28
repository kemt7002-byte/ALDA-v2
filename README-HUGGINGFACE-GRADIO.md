# Cấu hình tách lời thoại bằng Hugging Face Gradio

Bản này sửa `HuggingFaceSeparationClient.kt` để hỗ trợ Space Gradio công khai `abidlabs/music-separation`, không còn giả định mọi máy chủ đều có endpoint `/separate`.

## Cấu hình trong ALAD-Mobile

Trong Cài đặt → Hugging Face · Tách lời thoại khỏi nhạc nền, nhập một trong hai URL sau:

- `https://huggingface.co/spaces/abidlabs/music-separation`
- `https://abidlabs-music-separation.hf.space`

Để trống mục này nếu muốn dùng luồng dịch trực tiếp không tách nhạc.

## Cách client gọi Gradio

1. Thử API riêng `/separate` (dành cho Space Docker của ALAD nếu có).
2. Nếu endpoint đó không tồn tại, tải WAV lên `/gradio_api/upload`.
3. Gọi `/gradio_api/call/inference` và chờ kết quả SSE tại `/gradio_api/call/inference/{event_id}`.
4. Tải hai đầu ra theo thứ tự giao diện Space: Vocals, No Vocals / Instrumental.

## Giới hạn

- Space công khai có thể ngủ, quá tải hoặc thay đổi API; lỗi sẽ được báo trong trạng thái phiên.
- API Gradio xử lý từng tệp, không phải luồng âm thanh realtime. Pipeline hiện chia âm thanh thành đoạn 4 giây và dùng hàng đợi giới hạn để tránh độ trễ tăng vô hạn.
- Tách nguồn và đồng bộ phải được thử nghiệm trên thiết bị thật. Không đảm bảo nhạc nền được giữ hoàn hảo hay giọng dịch không trễ.
- Build APK chưa xác minh trong môi trường này vì Gradle cần tải bản phân phối từ Internet nhưng môi trường hiện không truy cập được `services.gradle.org`.
