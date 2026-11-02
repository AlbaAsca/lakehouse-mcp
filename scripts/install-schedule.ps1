# Crea (o rehace) la tarea programada de Windows que lanza los agentes cada N horas.
# Uso: powershell -ExecutionPolicy Bypass -File scripts\install-schedule.ps1 [-EveryHours 3] [-Start "09:00"] [-Hours 14]
# Lecciones aprendidas: en un portátil, Windows NO arranca tareas con batería por defecto (se saltan sin avisar) y no
# recupera las perdidas si el PC estaba apagado. Aquí se permiten ambas cosas.
param([int]$EveryHours = 3, [string]$Start = "09:00", [int]$Hours = 14)
$root = Split-Path -Parent $PSScriptRoot
$name = "$(Split-Path -Leaf $root) runner"          # el panel busca la tarea con este nombre
$bat = Join-Path $root "run-agents-scheduled.bat"
$action = New-ScheduledTaskAction -Execute $bat -WorkingDirectory $root
$trigger = New-ScheduledTaskTrigger -Daily -At $Start
$trigger.Repetition = (New-ScheduledTaskTrigger -Once -At $Start -RepetitionInterval (New-TimeSpan -Hours $EveryHours) `
                       -RepetitionDuration (New-TimeSpan -Hours $Hours)).Repetition
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
            -ExecutionTimeLimit (New-TimeSpan -Hours 3)
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
$i = Get-ScheduledTaskInfo -TaskName $name
Write-Output "Tarea '$name' lista. Próxima ejecución: $($i.NextRunTime)"
