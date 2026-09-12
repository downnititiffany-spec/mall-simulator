@echo off
cd /d "D:\Develop_code\GraduationProject"
"D:\Develop\JAVA17\bin\java.exe" -Dfile.encoding=UTF-8 -Dplatform.metric.publish.export-dir="D:\Develop_code\GraduationProject\metric-staging" -jar "D:\Develop_code\GraduationProject\analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar" 1> "D:\Develop_code\GraduationProject\docs\acceptance\v18-real-db-20260912\8091-run2-stdout.log" 2> "D:\Develop_code\GraduationProject\docs\acceptance\v18-real-db-20260912\8091-run2-stderr.log"
