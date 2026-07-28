import mimetypes
import os
from urllib.parse import quote, unquote

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, Response, FileResponse
from loguru import logger

from reactor_tool.model.protocal import FileRequest, FileListRequest, FileUploadRequest, get_file_id, get_legacy_file_id
from reactor_tool.util.middleware_util import RequestHandlerRoute
from reactor_tool.db.file_table_op import (
    FILE_SUCCESS,
    FileDB,
    FileInfoOp,
    get_file_preview_url,
    get_file_download_url,
    normalize_stored_file_name,
)
from reactor_tool.util.pptx_util import render_pptx_preview_html


router = APIRouter(route_class=RequestHandlerRoute)


async def _get_file_info_by_request_and_name(request_id: str, raw_file_name: str):
    """优先命中新的 basename 规则，同时兼容历史带子路径的 fileId。"""
    normalized_file_name = normalize_stored_file_name(raw_file_name)
    file_info = await FileInfoOp.get_by_file_id(file_id=get_file_id(request_id, normalized_file_name))
    if file_info:
        return file_info, normalized_file_name
    legacy_file_id = get_legacy_file_id(request_id, raw_file_name)
    file_info = await FileInfoOp.get_by_file_id(file_id=legacy_file_id)
    return file_info, normalized_file_name


async def _materialize_file(file_info):
    if not file_info:
        raise HTTPException(status_code=404, detail="File not found")
    if getattr(file_info, "status", FILE_SUCCESS) != FILE_SUCCESS:
        raise HTTPException(status_code=409, detail="File is not ready")
    try:
        if not await FileDB.ensure_local(file_info):
            raise HTTPException(status_code=404, detail="File not found")
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("Object storage restore failed: {}", file_info.file_path)
        raise HTTPException(status_code=503, detail="File storage unavailable") from exc


@router.post("/get_file")
async def get_file(
        body: FileRequest
):
    file_info = await FileInfoOp.get_by_file_id(file_id=body.file_id)
    if file_info:
        preview_url = get_file_preview_url(file_id=file_info.request_id, file_name=file_info.filename)
        download_url = get_file_download_url(file_id=file_info.request_id, file_name=file_info.filename)
        return JSONResponse(
            content={"fileId": file_info.file_id, "ossUrl": download_url, "downloadUrl": download_url, "domainUrl": preview_url, "requestId": body.request_id,
                     "fileName": body.file_name})
    else:
        raise HTTPException(status_code=404, detail="File not found")


@router.post("/upload_file")
async def upload_file(
        body: FileUploadRequest
):
    body.file_name = normalize_stored_file_name(body.file_name)
    body.request_id = body.request_id
    file_id = get_file_id(body.request_id, body.file_name)
    try:
        file_info = await FileInfoOp.add_by_content(
            filename=body.file_name, content=body.content, file_id=file_id, description=body.description,
            request_id=body.request_id)
    except ValueError as exc:
        raise HTTPException(status_code=413, detail=str(exc)) from exc
    preview_url = get_file_preview_url(file_id=file_info.request_id, file_name=file_info.filename)
    download_url = get_file_download_url(file_id=file_info.request_id, file_name=file_info.filename)
    return JSONResponse(content={"fileId": file_info.file_id, "ossUrl": download_url, "downloadUrl": download_url, "domainUrl": preview_url, "fileSize": file_info.file_size})

@router.post("/upload_file_data")
async def upload_file_data(file: UploadFile = File(...), request_id: str = Form(alias="requestId")):
    if not request_id.strip():
        raise HTTPException(status_code=422, detail="requestId must not be empty")
    file.filename = unquote(file.filename)
    file.filename = normalize_stored_file_name(file.filename)
    file_id = get_file_id(request_id, file.filename)
    try:
        file_info = await FileInfoOp.add_by_file(file=file, file_id=file_id, request_id=request_id)
    except ValueError as exc:
        raise HTTPException(status_code=413, detail=str(exc)) from exc
    preview_url = get_file_preview_url(file_id=file_info.request_id, file_name=file_info.filename)
    download_url = get_file_download_url(file_id=file_info.request_id, file_name=file_info.filename)
    return JSONResponse(content={"fileId": file_info.file_id, "downloadUrl": download_url, "domainUrl": preview_url, "fileSize": file_info.file_size})


@router.post("/get_file_list")
async def get_file_list(body: FileListRequest):
    if not body.filters:
        file_infos = await FileInfoOp.get_by_request_id(body.request_id)
    else:
        file_infos = await FileInfoOp.get_by_file_ids(file_ids=[f.file_id for f in body.filters])
        file_infos = [item for item in file_infos if item.status == FILE_SUCCESS]
    if not file_infos:
         return JSONResponse(content={"results": [], "totalSize": 0})
    total_size = sum([f.file_size for f in file_infos])
    results = []
    for file_info in file_infos:
        preview_url = get_file_preview_url(file_id=file_info.request_id, file_name=file_info.filename)
        download_url = get_file_download_url(file_id=file_info.request_id, file_name=file_info.filename)
        results.append({
            "fileId": file_info.file_id,
            "ossUrl": download_url,
            "downloadUrl": download_url, "domainUrl": preview_url,
            "requestId": file_info.request_id, "fileName": file_info.filename
        })
    return JSONResponse(content={"results": results, "totalSize": total_size})


@router.delete("/{file_id}")
async def delete_file(file_id: str):
    try:
        await FileInfoOp.delete(file_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="File not found") from exc
    except Exception as exc:
        logger.exception("Object storage delete failed: {}", file_id)
        raise HTTPException(status_code=503, detail="File storage unavailable") from exc
    return JSONResponse(content={"fileId": file_id, "deleted": True})


@router.get("/download/{file_id}/{file_name:path}")
async def download_file(file_id: str, file_name: str):
    # TODO 目前 file_id 实际上是 request_id，后续统一修改
    file_info, file_name = await _get_file_info_by_request_and_name(file_id, file_name)
    await _materialize_file(file_info)
    return FileResponse(file_info.file_path, filename=os.path.basename(file_name))


@router.get("/preview/{file_id}/{file_name:path}")
async def preview_file(file_id: str, file_name: str):
    # TODO 目前 file_id 实际上是 request_id，后续统一修改
    file_info, file_name = await _get_file_info_by_request_and_name(file_id, file_name)
    await _materialize_file(file_info)

    if file_name.lower().endswith(".pptx"):
        try:
            return HTMLResponse(
                render_pptx_preview_html(file_info.file_path),
                headers={
                    "Cache-Control": "private, max-age=60",
                    "X-Content-Type-Options": "nosniff",
                },
            )
        except Exception:
            logger.exception("PPTX preview rendering failed: {}", file_info.file_path)
            return Response(content="PPTX preview unavailable", status_code=422)

    disposition = "inline"
    if file_name.endswith(".md"):
        content_type = "text/markdown"
    else:
        content_type, _ = mimetypes.guess_type(file_name)
    if not content_type:
        content_type = "application/octet-stream"
        disposition = "attachment"

    encoded_file_name = quote(file_name)

    return FileResponse(
        file_info.file_path,
        filename=os.path.basename(file_name),
        media_type=content_type,
        headers={
            "Content-Disposition": f"{disposition}; filename=\"{encoded_file_name}\"; filename*=UTF-8''{encoded_file_name}",
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type, Authorization",
        }
    )

