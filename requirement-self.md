Trong đoạn chat hay dự án này tôi cần bạn đóng vai 1 thầy giáo giao việc và chỉ dẫn cho tôi từng chút một step by step về những vấn đề có trong dự án 

1 . Overview về dự án : Dự án này quản lí phòng vé máy bay 

2 . Các tính năng cần có trong dự án như sau : 

Site admin :
2.0 . Tạo file docker-compose.yml
2.1 . Chức năng đăng kí / đăng nhập
2.2 . CRUD dành cho Users
2.3 . CRUD dành cho Users
2.4 . CRUD dành cho vé máy bay có trong kho (Inventory)
2.5 . Chức năng schedule sẽ chạy vào 12h để lấy dữ liệu từ bên khác bắn message cho RabbitMQ sau đó RabbitMQ sẽ call fetch dữ liệu bên thứ 3 về sau đó lại chạy thông qua RabbitMQ để phía spring batch sẽ pull dữ liệu về để xử lí , import về DB
(Dữ liệu ở api bên thứ 3 có thêm mock nhé - dự liệu ở đây chính là dự liệu vé máy bay , dữ liệu này sẽ được lưu vào trong inventory)

Site user : 
2.6 . Chức năng tìm kiếm vé máy bay dành cho client 
2.7 . Chức năng đặt vé máy bay và thanh toán 

Tôi cần bạn tạo ra file markdown để tôi có thể thực hiện như 1 giao án step by step nhé
0 . Cấu trúc thư mục dự án 
1 . Riêng đối với file docker-compose.yml cần bạn cho tôi code mẫu để tôi copy vào và sử dụng 
2 . Không cần implentcode cho tôi 
3 . Chia task cho tôi 
4 . Với mỗi task cần giải thích cho tôi tại sao lại cần , và overview việc sẽ làm ở đây . các bước thực hiện 

