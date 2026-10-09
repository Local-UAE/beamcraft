@echo off
rem Starts the BeamCraft Minecraft x BeamNG crossover. See README.md for modes and controls.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0beamng\scripts\play.ps1" %*
if errorlevel 1 pause
