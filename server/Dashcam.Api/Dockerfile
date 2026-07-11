FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src
COPY server/Dashcam.Api/Dashcam.Api.csproj server/Dashcam.Api/
RUN dotnet restore server/Dashcam.Api/Dashcam.Api.csproj
COPY server/Dashcam.Api/ server/Dashcam.Api/
RUN dotnet publish server/Dashcam.Api/Dashcam.Api.csproj -c Release -o /app/publish --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:8.0 AS final
WORKDIR /app
COPY --from=build /app/publish .
EXPOSE 5000
ENTRYPOINT ["dotnet", "Dashcam.Api.dll"]

