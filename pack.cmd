@echo off
setlocal EnableDelayedExpansion
rem chcp 65001 > NUL

:: Путь к 7-Zip (замените, если 7z.exe находится в другом месте)
set SEVENZIP=D:\Progs\7Z\7z.exe

:: Имя архива (будет создано в текущей папке с текущей датой)
set "ARCHIVE_NAME=RollPeriodMeter_archive_%date:~6,4%%date:~3,2%%date:~0,2%-%time:~0,2%%time:~3,2%%time:~6,2%.zip"

:: Папка для архивации (текущая папка, где находится .bat)
set SOURCE_DIR="app" "build.gradle.*"

:: Список папок и файлов для исключения (добавьте свои папки/файлы через пробел)
set EXCLUDE=-xr^^!node_modules -xr^^!.git -xr^^!*.log -xr^^!build -xr^^!.gitignore -xr^^!app\release -xr^^!app\debug

echo EXCLUDE=!EXCLUDE!


:: Проверка наличия 7-Zip
if not exist "%SEVENZIP%" (
    echo Ошибка: 7z.exe не найден по пути %SEVENZIP%. Укажите правильный путь.
    pause
    exit /b 1
)

:: Создание архива
echo Создание архива %ARCHIVE_NAME%...

%SEVENZIP% a -tzip "%ARCHIVE_NAME%" %SOURCE_DIR% !EXCLUDE! -bb1

:: Проверка успешности архивации
if %ERRORLEVEL%==0 (
    echo Архив %ARCHIVE_NAME% успешно создан!
) else (
    echo Ошибка при создании архива.
	
)
