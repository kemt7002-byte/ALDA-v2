# ALAD-Mobile FIX2 — Luồng tách lời, dịch và lồng tiếng

Bản này tiếp tục từ `ALAD-Mobile-Live-Separation-Integrated-FIX1.zip`; không thay thế bản FIX1.

## Luồng dự kiến
1. Android MediaProjection thu âm thanh nội bộ mà hệ điều hành/ứng dụng nguồn cho phép.
2. Âm thanh PCM 16 kHz mono được chia thành các đoạn 4 giây.
3. `HuggingFaceSeparationClient` gọi API tách nguồn: ưu tiên `/separate` của Space Docker do người dùng tự triển khai; nếu không có thì thử Gradio API của `abidlabs/music-separation`.
4. Phần vocals được gửi vào WebSocket Gemini Live đã cấu hình API key. Gemini xử lý/dịch theo hướng dẫn ngữ cảnh và trả âm thanh tiếng Việt.
5. Phần instrumental được xếp hàng trước phần vocals; `AudioPlayerManager` giữ nó chờ tới khi nhận khung âm thanh tiếng Việt đầu tiên của đoạn, rồi phát trên AudioTrack riêng.
6. AudioTrack giọng Việt và AudioTrack nhạc nền được Android trộn lại. Mức âm lượng nền và giọng Việt có thể điều chỉnh riêng.

## Cấu hình Hugging Face
- URL Space công khai có thể nhập là `https://huggingface.co/spaces/abidlabs/music-separation`.
- Nếu dùng Space riêng chạy `huggingface-space/` trong ZIP, nhập URL `https://<username>-<space-name>.hf.space`.
- API key Gemini vẫn cần trong cài đặt ứng dụng để dịch và tạo giọng nói. Token Hugging Face chỉ cần nếu Space riêng tư hoặc API yêu cầu xác thực.

## Giới hạn đã biết
- Đây là xử lý theo đoạn 4 giây, không phải tách âm thanh sample-by-sample. Demucs trên CPU miễn phí có thể chậm hơn thời gian phát; hàng đợi có giới hạn và có thể bỏ đoạn khi quá tải.
- Âm thanh gốc được giảm/tắt qua âm lượng STREAM_MUSIC của Android, tác động đến âm thanh media toàn hệ thống, không chỉ riêng video đang dịch.
- Đồng bộ giữa nhạc nền và giọng Việt là đồng bộ gần đúng: Gemini có độ trễ thay đổi theo mạng và thời gian tạo âm thanh.
- Chưa xác nhận build APK hoặc kiểm thử end-to-end trên thiết bị trong môi trường này. Cần build và thử thực tế trước khi coi tính năng đã hoàn thành.
