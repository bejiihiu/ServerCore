package kz.bejiihiu.servercore.diagnostics

import kz.bejiihiu.servercore.boot.BarsInstaller
import kz.bejiihiu.servercore.boot.EspInstaller
import kz.bejiihiu.servercore.boot.FlectonePulseInstaller
import kz.bejiihiu.servercore.boot.GatewayInstaller
import kz.bejiihiu.servercore.boot.GuestInstaller
import kz.bejiihiu.servercore.boot.PacketInstaller
import kz.bejiihiu.servercore.boot.PapiInstaller
import kz.bejiihiu.servercore.boot.ProtectionInstaller
import kz.bejiihiu.servercore.boot.SpecInstaller

/**
 * the live installers the probe reads. grouped in one object so the boot
 * wiring stays one readable literal instead of a nine-argument constructor
 * whose order nobody can remember.
 */
data class DiagnosticsSources(
    val guest: GuestInstaller,
    val packet: PacketInstaller,
    val esp: EspInstaller,
    val protection: ProtectionInstaller,
    val spec: SpecInstaller,
    val bars: BarsInstaller,
    val papi: PapiInstaller,
    val gateway: GatewayInstaller,
    val flectonePulse: FlectonePulseInstaller,
)
