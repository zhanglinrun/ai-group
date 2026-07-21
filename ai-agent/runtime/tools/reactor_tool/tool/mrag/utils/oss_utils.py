"""Compatibility facade for historical MRAG imports."""

from urllib import request

from .local_file_utils import upload_local_storage
from .logger_utils import logger
from .minio_utils import (
    create_permanent_download_url,
    download_file,
    generate_secure_token,
    get_file_extension,
    router,
    upload_minio,
    upload_oss,
    verify_token,
)

__all__ = [
    "create_permanent_download_url",
    "download",
    "download_file",
    "generate_secure_token",
    "get_file_extension",
    "router",
    "upload_local_storage",
    "upload_minio",
    "upload_oss",
    "verify_token",
]


def download(file_url, save_path):
    logger.info(file_url)
    logger.info("文件开始下载... 来源:{}".format(file_url))
    extension = get_file_extension(file_url)
    file_name = save_path + extension
    try:
        request.urlretrieve(file_url, file_name)
        logger.info("文件下载完成,地址:{}".format(save_path))
        return "success", save_path
    except Exception:
        logger.info("文件下载失败!")
        return "failed", ""
