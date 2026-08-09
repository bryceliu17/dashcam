using Dashcam.Api.Models;
using Microsoft.EntityFrameworkCore;

namespace Dashcam.Api.Data;

public sealed class DashcamDbContext(DbContextOptions<DashcamDbContext> options) : DbContext(options)
{
    public DbSet<Video> Videos => Set<Video>();
    public DbSet<AudioRecording> AudioRecordings => Set<AudioRecording>();
    public DbSet<DeviceStatus> DeviceStatuses => Set<DeviceStatus>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        var video = modelBuilder.Entity<Video>();
        video.HasKey(x => x.Id);
        video.Property(x => x.Filename).HasMaxLength(255).IsRequired();
        video.Property(x => x.OriginalFilename).HasMaxLength(255).IsRequired();
        video.Property(x => x.FilePath).HasMaxLength(2048).IsRequired();
        video.HasIndex(x => x.StartTime);
        video.HasIndex(x => x.Locked);

        var audio = modelBuilder.Entity<AudioRecording>();
        audio.HasKey(x => x.Id);
        audio.Property(x => x.Filename).HasMaxLength(255).IsRequired();
        audio.Property(x => x.OriginalFilename).HasMaxLength(255).IsRequired();
        audio.Property(x => x.FilePath).HasMaxLength(2048).IsRequired();
        audio.HasIndex(x => x.StartTime);
        audio.HasIndex(x => x.Locked);

        var device = modelBuilder.Entity<DeviceStatus>();
        device.HasKey(x => x.DeviceId);
        device.Property(x => x.DeviceId).HasMaxLength(128);
        device.Property(x => x.DeviceName).HasMaxLength(160).IsRequired();
        device.Property(x => x.Manufacturer).HasMaxLength(80).IsRequired();
        device.Property(x => x.Model).HasMaxLength(120).IsRequired();
        device.Property(x => x.AndroidVersion).HasMaxLength(80).IsRequired();
        device.Property(x => x.AppVersion).HasMaxLength(40).IsRequired();
        device.Property(x => x.IpAddress).HasMaxLength(45).IsRequired();
        device.Property(x => x.ChargingSource).HasMaxLength(32).IsRequired();
        device.Property(x => x.LiveError).HasMaxLength(500).IsRequired();
        device.Property(x => x.LastSeenTransport).HasMaxLength(16).IsRequired();
        device.HasIndex(x => x.LastSeenAt);
    }
}
